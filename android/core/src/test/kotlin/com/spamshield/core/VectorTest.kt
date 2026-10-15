package com.spamshield.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs every case in shared/test-vectors against the Kotlin engine. */
class VectorTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = Engine.loadBundled()
    private val classifier = Classifier(Engine.parseModel(readBundled(Engine.MODEL_RESOURCE)))

    private fun vectors(name: String): List<JsonObject> {
        val dir = System.getProperty("spamshield.vectors")?.let(::File) ?: File("../../shared/test-vectors")
        val file = File(dir, "$name.json")
        assertTrue(file.isFile, "missing vector file $file")
        return json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun checkAll(name: String, cases: List<JsonObject>, check: (JsonObject) -> String?) {
        assertTrue(cases.isNotEmpty(), "$name has no cases")
        val failures = cases.mapIndexedNotNull { i, c -> check(c)?.let { "$name[$i]: $it" } }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun allVectorFilesAreCovered() {
        val dir = System.getProperty("spamshield.vectors")?.let(::File) ?: File("../../shared/test-vectors")
        val known = setOf("normalize.json", "patterns.json", "tokenize.json", "classifier.json", "engine.json")
        val present = dir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name }.toSet()
        assertEquals(emptySet(), present - known, "new vector files need a test")
    }

    @Test
    fun normalize() = checkAll("normalize", vectors("normalize")) { c ->
        val actual = Numbers.normalize(c.str("raw"), c.str("countryCode") ?: "1")
        if (actual != c.str("expected")) "normalize(${c["raw"]}) = $actual, expected ${c["expected"]}" else null
    }

    @Test
    fun patterns() = checkAll("patterns", vectors("patterns")) { c ->
        val expected = c["matches"]!!.jsonPrimitive.boolean
        val actual = Patterns.matches(c.str("pattern")!!, c.str("number")!!)
        if (actual != expected) "${c["pattern"]} vs ${c["number"]}: $actual" else null
    }

    @Test
    fun tokenize() = checkAll("tokenize", vectors("tokenize")) { c ->
        val expected = c["tokens"]!!.jsonArray.map { it.jsonPrimitive.content }
        val actual = classifier.tokenize(c.str("text"))
        if (actual != expected) "tokenize(${c["text"]}) = $actual, expected $expected" else null
    }

    @Test
    fun classifier() = checkAll("classifier", vectors("classifier")) { c ->
        val expected = c["probability"]!!.jsonPrimitive.double
        val actual = classifier.probability(c.str("text"))
        if (abs(actual - expected) > 1e-9) "p(${c["text"]}) = $actual, expected $expected" else null
    }

    @Test
    fun engine() = checkAll("engine", vectors("engine")) { c ->
        val event = json.decodeFromJsonElement<Event>(c["event"]!!)
        val context = c["context"]?.let { json.decodeFromJsonElement<EngineContext>(it) } ?: EngineContext()
        val expect = c["expect"]!!.jsonObject
        val verdict = engine.evaluate(event, context)
        val action = Action.valueOf(expect.str("action")!!)
        val reasons = (expect["reasons"] as JsonArray).map { it.jsonPrimitive.content }
        val score = expect["score"]!!.jsonPrimitive.double
        if (verdict.action != action || verdict.reasons != reasons || abs(verdict.score - score) > 1e-9) {
            "${c.str("name")}: got $verdict, expected $action $reasons $score"
        } else {
            null
        }
    }

    private fun readBundled(path: String) =
        javaClass.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
}
