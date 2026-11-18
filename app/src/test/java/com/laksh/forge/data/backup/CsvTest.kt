package com.laksh.forge.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CsvTest {

    @Test
    fun `quoted fields keep delimiters, quotes and line breaks`() {
        val rows = Csv.parse("a,b,c\r\n\"x, y\",\"say \"\"hi\"\"\",\"line1\nline2\"\r\n")
        assertThat(rows).hasSize(2)
        assertThat(rows[1]).containsExactly("x, y", "say \"hi\"", "line1\nline2").inOrder()
    }

    @Test
    fun `bom and blank lines are ignored`() {
        val rows = Csv.parse("﻿name,kcal\n\nRice,200\n")
        assertThat(rows).containsExactly(listOf("name", "kcal"), listOf("Rice", "200")).inOrder()
    }

    @Test
    fun `semicolon and tab delimiters are detected from the header`() {
        assertThat(Csv.detectDelimiter("a;b;c\n1;2;3")).isEqualTo(';')
        assertThat(Csv.detectDelimiter("a\tb\tc")).isEqualTo('\t')
        assertThat(Csv.detectDelimiter("\"a;b\",c,d")).isEqualTo(',')
    }

    @Test
    fun `empty trailing fields are kept`() {
        val rows = Csv.parse("a,b,c\n1,,\n")
        assertThat(rows[1]).containsExactly("1", "", "").inOrder()
    }

    @Test
    fun `escaping quotes only when needed`() {
        assertThat(Csv.escape("plain")).isEqualTo("plain")
        assertThat(Csv.escape("a,b")).isEqualTo("\"a,b\"")
        assertThat(Csv.escape("he said \"no\"")).isEqualTo("\"he said \"\"no\"\"\"")
        assertThat(Csv.escape(null)).isEqualTo("")
    }

    @Test
    fun `spreadsheet formulas are neutralised but numbers are not`() {
        assertThat(Csv.escape("=HYPERLINK(\"x\")")).startsWith("\"'=")
        assertThat(Csv.escape("+1 roti")).isEqualTo("'+1 roti")
        assertThat(Csv.escape("-2.5")).isEqualTo("-2.5")
    }

    @Test
    fun `written lines parse back to the same values`() {
        val values = listOf("Dal, tadka", "1 katori \"small\"", "", "note\nwith break")
        val parsed = Csv.parse(Csv.line(values) + "\r\n")
        assertThat(parsed.single()).containsExactlyElementsIn(values).inOrder()
    }

    @Test
    fun `table lookups ignore case, spaces and underscores`() {
        val t = CsvTable(listOf(listOf("Exercise Name", "set_order", "Weight (kg)")))
        assertThat(t.col("exercise_name")).isEqualTo(0)
        assertThat(t.col("Set Order")).isEqualTo(1)
        assertThat(t.col("weightkg")).isEqualTo(2)
        assertThat(t.col("reps")).isNull()
        assertThat(t.has("missing", "EXERCISE NAME")).isTrue()
    }
}
