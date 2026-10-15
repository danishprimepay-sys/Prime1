FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    DATABASE_PATH=/data/spamshield.sqlite3

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY spamshield_api ./spamshield_api

RUN useradd --system --uid 10001 --home-dir /app spamshield \
    && mkdir -p /data && chown spamshield /data
USER spamshield
VOLUME ["/data"]

EXPOSE 8080
CMD ["uvicorn", "spamshield_api.main:app", "--host", "0.0.0.0", "--port", "8080", "--no-server-header"]
