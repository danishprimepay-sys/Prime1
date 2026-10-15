"""Train the on-device SMS spam classifier and export shared/model/sms_classifier.json.

Model: L2-regularised logistic regression over binary (presence) token features,
trained with Adagrad. Inference is just `bias + sum(weights)` -> sigmoid, which
the Kotlin and Swift engines reproduce exactly (see shared/SPEC.md section 5).

Data:
  * SMS Spam Collection (Almeida & Hidalgo, UCI ML Repository, CC BY 4.0),
    downloaded at train time, never committed.
  * ml/data/supplement.tsv: hand-written modern (US-style) examples, upweighted
    because the UCI corpus dates from 2011.

Usage: python3 ml/train.py [--data path/to/SMSSpamCollection.tsv]
Standard library only.
"""

from __future__ import annotations

import argparse
import io
import json
import math
import random
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "reference"))
from spamshield_ref import URL_PATTERN, tokenize  # noqa: E402

DATA_DIR = ROOT / "ml" / "data"
CACHE = DATA_DIR / "SMSSpamCollection.tsv"
SUPPLEMENT = DATA_DIR / "supplement.tsv"
MODEL_OUT = ROOT / "shared" / "model" / "sms_classifier.json"
METRICS_OUT = ROOT / "ml" / "metrics.json"

SOURCES = [
    ("zip", "https://archive.ics.uci.edu/static/public/228/sms+spam+collection.zip"),
    ("tsv", "https://raw.githubusercontent.com/justmarkham/pycon-2016-tutorial/master/data/sms.tsv"),
]

REPLACEMENTS = [
    {"pattern": URL_PATTERN.pattern, "token": "__url__"},
    {"pattern": r"[+]?[(]?[0-9]{3}[)]?[-. ]?[0-9]{3}[-. ]?[0-9]{4}(?![0-9])", "token": "__phone__"},
    {"pattern": r"[$£€][0-9][0-9,]*([.][0-9]+)?|[0-9][0-9,]*([.][0-9]+)? ?(dollars|usd|pounds)", "token": "__money__"},
    {"pattern": r"[0-9]{5,}", "token": "__longnum__"},
]
MIN_TOKEN_LENGTH = 2
MIN_DOC_FREQ = 3
SUPPLEMENT_WEIGHT = 2.0
L2 = 1e-4
EPOCHS = 40
LEARNING_RATE = 0.5
PRUNE_BELOW = 0.02
SEED = 7


def load_tsv(text: str) -> list[tuple[str, str]]:
    rows = []
    for line in text.splitlines():
        if "\t" not in line:
            continue
        label, body = line.split("\t", 1)
        if label in ("ham", "spam"):
            rows.append((label, body))
    return rows


def fetch_corpus() -> str:
    if CACHE.exists():
        return CACHE.read_text(encoding="utf-8")
    for kind, url in SOURCES:
        try:
            print(f"downloading {url}", file=sys.stderr)
            with urllib.request.urlopen(url, timeout=60) as resp:
                payload = resp.read()
        except Exception as exc:  # try the next mirror
            print(f"  failed: {exc}", file=sys.stderr)
            continue
        if kind == "zip":
            with zipfile.ZipFile(io.BytesIO(payload)) as zf:
                text = zf.read("SMSSpamCollection").decode("utf-8")
        else:
            text = payload.decode("utf-8")
        CACHE.write_text(text, encoding="utf-8")
        return text
    raise SystemExit("could not download the SMS Spam Collection; pass --data")


def featurize(rows, compiled):
    return [(tokenize(body, compiled, MIN_TOKEN_LENGTH), 1.0 if label == "spam" else 0.0, w) for label, body, w in rows]


def train(examples, vocab):
    rnd = random.Random(SEED)
    weights = {t: 0.0 for t in vocab}
    grad_sq = {t: 1e-8 for t in vocab}
    bias, bias_sq = 0.0, 1e-8
    order = list(range(len(examples)))
    for _ in range(EPOCHS):
        rnd.shuffle(order)
        for i in order:
            tokens, y, w = examples[i]
            feats = [t for t in tokens if t in weights]
            z = bias + sum(weights[t] for t in feats)
            p = 1.0 / (1.0 + math.exp(-max(-30.0, min(30.0, z))))
            g = (p - y) * w
            bias_sq += g * g
            bias -= LEARNING_RATE * g / math.sqrt(bias_sq)
            for t in feats:
                gt = g + L2 * weights[t]
                grad_sq[t] += gt * gt
                weights[t] -= LEARNING_RATE * gt / math.sqrt(grad_sq[t])
    return bias, weights


def predict(bias, weights, tokens):
    z = bias + sum(weights.get(t, 0.0) for t in sorted(tokens))
    return 1.0 / (1.0 + math.exp(-z))


def evaluate(bias, weights, examples, threshold):
    tp = fp = fn = tn = 0
    for tokens, y, _ in examples:
        spam = predict(bias, weights, tokens) >= threshold
        if spam and y:
            tp += 1
        elif spam:
            fp += 1
        elif y:
            fn += 1
        else:
            tn += 1
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {
        "threshold": threshold, "precision": round(precision, 4), "recall": round(recall, 4),
        "falsePositiveRate": round(fp / (fp + tn), 4) if fp + tn else 0.0,
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, help="local SMS Spam Collection TSV (label<TAB>text)")
    args = parser.parse_args()

    corpus = load_tsv(args.data.read_text(encoding="utf-8") if args.data else fetch_corpus())
    supplement = load_tsv(SUPPLEMENT.read_text(encoding="utf-8"))
    rows = [(l, b, 1.0) for l, b in corpus] + [(l, b, SUPPLEMENT_WEIGHT) for l, b in supplement]

    rnd = random.Random(SEED)
    rnd.shuffle(rows)
    split = int(len(rows) * 0.8)
    compiled = [(re.compile(r["pattern"]), r["token"]) for r in REPLACEMENTS]
    train_ex = featurize(rows[:split], compiled)
    test_ex = featurize(rows[split:], compiled)

    def build_vocab(examples):
        df: dict[str, int] = {}
        for tokens, _, _ in examples:
            for t in tokens:
                df[t] = df.get(t, 0) + 1
        return {t for t, c in df.items() if c >= MIN_DOC_FREQ}

    bias, weights = train(train_ex, build_vocab(train_ex))
    weights = {t: w for t, w in weights.items() if abs(w) >= PRUNE_BELOW}
    metrics = {
        "heldOut": [evaluate(bias, weights, test_ex, th) for th in (0.5, 0.7, 0.9)],
        "trainExamples": len(train_ex), "testExamples": len(test_ex),
    }

    # Final model: retrain on everything with the same hyper-parameters.
    all_ex = train_ex + test_ex
    bias, weights = train(all_ex, build_vocab(all_ex))
    weights = {t: round(w, 6) for t, w in sorted(weights.items()) if abs(w) >= PRUNE_BELOW}
    model = {
        "version": 1,
        "bias": round(bias, 6),
        "tokenizer": {"replacements": REPLACEMENTS, "minTokenLength": MIN_TOKEN_LENGTH},
        "weights": weights,
        "source": "ml/train.py; SMS Spam Collection (Almeida & Hidalgo, UCI, CC BY 4.0) + ml/data/supplement.tsv",
    }
    MODEL_OUT.parent.mkdir(parents=True, exist_ok=True)
    MODEL_OUT.write_text(json.dumps(model, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    metrics["vocabulary"] = len(weights)
    METRICS_OUT.write_text(json.dumps(metrics, indent=2) + "\n")
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
