package com.laksh.forge.data.backup

/**
 * Minimal RFC 4180 CSV support. Hand-rolled because the app has no CSV dependency and the
 * needs are small: quoted fields with embedded delimiters, doubled quotes and line breaks,
 * CRLF or LF, a UTF-8 BOM, and either "," or ";" as the delimiter (Strong writes ";" in
 * locales that use a decimal comma).
 */
object Csv {

    /** Parse a whole document into rows of fields. Blank lines are dropped. */
    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val src = text.removePrefix("﻿")
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = src.length

        fun endField() {
            row += field.toString()
            field.setLength(0)
        }

        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isBlank())) rows += row.toList()
            row.clear()
        }

        while (i < n) {
            val c = src[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && src[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    delimiter -> endField()
                    '\r' -> {
                        endRow()
                        if (i + 1 < n && src[i + 1] == '\n') i++
                    }
                    '\n' -> endRow()
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** Pick "," or ";" by which one splits the header line into more fields outside quotes. */
    fun detectDelimiter(text: String): Char {
        val header = text.removePrefix("﻿").lineSequence().firstOrNull().orEmpty()
        var commas = 0
        var semis = 0
        var tabs = 0
        var q = false
        header.forEach { c ->
            when {
                c == '"' -> q = !q
                q -> Unit
                c == ',' -> commas++
                c == ';' -> semis++
                c == '\t' -> tabs++
            }
        }
        return when {
            semis > commas && semis >= tabs -> ';'
            tabs > commas && tabs > semis -> '\t'
            else -> ','
        }
    }

    /** Quote a field only when it needs it, and neutralise spreadsheet formula injection. */
    fun escape(value: String?): String {
        if (value == null) return ""
        // A cell starting with = + - @ is executed as a formula by Excel/Sheets. Food names
        // and notes are free text, so prefix them with an apostrophe the way spreadsheets do.
        val safe = if (value.isNotEmpty() && value[0] in "=+-@" && value.toDoubleOrNull() == null) "'$value" else value
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun line(values: List<String?>): String = values.joinToString(",") { escape(it) }
}

/**
 * Header-driven access to a parsed CSV so column order never matters. Header names are
 * matched case-insensitively and ignoring spaces, underscores and punctuation, so
 * "Exercise Name", "exercise_name" and "exerciseName" are the same column.
 */
class CsvTable(rows: List<List<String>>) {
    val header: List<String> = rows.firstOrNull().orEmpty()
    val body: List<List<String>> = if (rows.isEmpty()) emptyList() else rows.drop(1)

    private val index: Map<String, Int> = header.mapIndexed { i, h -> norm(h) to i }.toMap()

    fun has(vararg names: String): Boolean = names.any { index.containsKey(norm(it)) }

    fun col(vararg names: String): Int? = names.firstNotNullOfOrNull { index[norm(it)] }

    companion object {
        fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
    }
}

fun List<String>.cell(index: Int?): String? =
    index?.let { getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
