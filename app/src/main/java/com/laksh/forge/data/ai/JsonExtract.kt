package com.laksh.forge.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** The one Json configuration the AI layer uses for parsing provider replies. */
object AiJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}

/**
 * Recovers a JSON object from whatever a model actually wrote.
 *
 * Providers that enforce the schema return clean JSON, but the json_object and prompt-only
 * fallbacks (DeepSeek, Mistral, custom endpoints) regularly wrap it in prose, markdown fences
 * or a reasoning preamble, and occasionally leave a trailing comma. Rather than failing the
 * whole meal estimate over that, this tries progressively looser readings and, when several
 * objects appear, prefers the one that has the keys the schema requires.
 *
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
object JsonExtract {

    private val thinkBlock = Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE)
    private val fence = Regex("```[a-zA-Z0-9_-]*\\s*\\n?([\\s\\S]*?)```")
    private val trailingComma = Regex(",\\s*([}\\]])")

    /**
     * @param requiredKeys top-level keys a good answer must have; used to choose between
     *   several embedded objects (e.g. an echoed example vs. the real answer).
     * @return the best object found, or null when nothing parses.
     */
    fun extractObject(text: String?, requiredKeys: Set<String> = emptySet()): JsonObject? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.removePrefix("﻿").replace(thinkBlock, " ").trim()
        if (cleaned.isEmpty()) return null

        // 1. The whole reply is the object (the normal case).
        parseObject(cleaned)?.let { if (hasKeys(it, requiredKeys)) return it }

        // 2. Inside markdown fences.
        val fenced = fence.findAll(cleaned).mapNotNull { parseObject(it.groupValues[1].trim()) }.toList()
        pick(fenced, requiredKeys)?.let { return it }

        // 3. Any balanced {...} region in the text.
        val candidates = balancedObjects(cleaned).mapNotNull { parseObject(it) }
        pick(candidates, requiredKeys)?.let { return it }

        // 4. Nothing had the required keys; settle for the first object at all, so the parser
        // can still fill defaults rather than throwing away a mostly-right answer.
        return (listOfNotNull(parseObject(cleaned)) + fenced + candidates).firstOrNull()
    }

    /** Top-level keys listed in a JSON schema's "required" array. */
    fun requiredKeys(schema: JsonObject): Set<String> =
        runCatching {
            schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
        }.getOrNull() ?: emptySet()

    private fun pick(objects: List<JsonObject>, requiredKeys: Set<String>): JsonObject? {
        if (objects.isEmpty()) return null
        val complete = objects.filter { hasKeys(it, requiredKeys) }
        // The longest complete object is the real answer far more often than a short echo.
        return complete.maxByOrNull { it.toString().length }
    }

    private fun hasKeys(obj: JsonObject, keys: Set<String>): Boolean = keys.all { it in obj }

    /** Parse [s] as an object, tolerating trailing commas and a double-encoded string. */
    private fun parseObject(s: String): JsonObject? {
        val direct = parseElement(s) ?: parseElement(s.replace(trailingComma, "$1")) ?: return null
        return when (direct) {
            is JsonObject -> direct
            // Some gateways return the JSON as a quoted string: "{\"name\": ...}"
            is JsonPrimitive -> if (direct.isString) {
                (parseElement(direct.content) as? JsonObject)
                    ?: (parseElement(direct.content.replace(trailingComma, "$1")) as? JsonObject)
            } else null
            else -> null
        }
    }

    private fun parseElement(s: String): JsonElement? =
        runCatching { AiJson.json.parseToJsonElement(s) }.getOrNull()

    /**
     * Every top-level balanced `{...}` span, scanning with awareness of string literals so a
     * brace inside "a {quoted} note" does not break the count.
     */
    internal fun balancedObjects(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> if (depth > 0) inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += text.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return out
    }
}
