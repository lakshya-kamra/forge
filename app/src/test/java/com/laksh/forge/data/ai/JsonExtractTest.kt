package com.laksh.forge.data.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class JsonExtractTest {

    private fun name(text: String, required: Set<String> = emptySet()): String? =
        JsonExtract.extractObject(text, required)?.get("name")?.jsonPrimitive?.content

    @Test fun plainObject() {
        assertThat(name("""{"name":"Dal","kcal":420}""")).isEqualTo("Dal")
    }

    @Test fun nullBlankAndProseOnlyGiveNull() {
        assertThat(JsonExtract.extractObject(null)).isNull()
        assertThat(JsonExtract.extractObject("   ")).isNull()
        assertThat(JsonExtract.extractObject("I can't see a meal in this photo.")).isNull()
    }

    @Test fun markdownFence() {
        val text = "Here you go:\n```json\n{\"name\": \"Poha\", \"kcal\": 350}\n```\nEnjoy!"
        assertThat(name(text)).isEqualTo("Poha")
    }

    @Test fun fenceWithoutLanguage() {
        assertThat(name("```\n{\"name\":\"Idli\"}\n```")).isEqualTo("Idli")
    }

    @Test fun proseAroundObject() {
        assertThat(name("Sure! {\"name\":\"Rajma chawal\",\"kcal\":610} Let me know.")).isEqualTo("Rajma chawal")
    }

    @Test fun bracesInsideStringsDoNotBreakTheScan() {
        val text = "Result: {\"name\":\"Curly {brace} soup\",\"note\":\"a } in text\"} done"
        assertThat(name(text)).isEqualTo("Curly {brace} soup")
    }

    @Test fun escapedQuotesInsideStrings() {
        val text = "x {\"name\":\"6\\\" sub\",\"kcal\":500} y"
        assertThat(name(text)).isEqualTo("6\" sub")
    }

    @Test fun trailingCommasAreRepaired() {
        assertThat(name("{\"name\":\"Paneer\",\"kcal\":300,}")).isEqualTo("Paneer")
        assertThat(name("{\"name\":\"Thali\",\"components\":[{\"name\":\"rice\"},],}")).isEqualTo("Thali")
    }

    @Test fun thinkingBlockIsIgnored() {
        val text = "<think>Maybe {\"name\":\"wrong\"} or not</think>{\"name\":\"Upma\",\"kcal\":300}"
        assertThat(name(text, setOf("name", "kcal"))).isEqualTo("Upma")
    }

    @Test fun prefersTheObjectWithRequiredKeys() {
        val text = "Example shape: {\"name\":\"...\"} Answer: {\"name\":\"Biryani\",\"kcal\":800,\"protein_g\":30}"
        assertThat(name(text, setOf("name", "kcal", "protein_g"))).isEqualTo("Biryani")
    }

    @Test fun fallsBackToAnyObjectWhenNoneHasAllRequiredKeys() {
        assertThat(name("{\"name\":\"Partial\"}", setOf("name", "kcal"))).isEqualTo("Partial")
    }

    @Test fun nestedObjectsAreReturnedWhole() {
        val obj = JsonExtract.extractObject("ok {\"name\":\"Plate\",\"inner\":{\"a\":{\"b\":1}}} ok")
        assertThat(obj).isNotNull()
        assertThat(obj!!.keys).containsExactly("name", "inner")
    }

    @Test fun doubleEncodedString() {
        assertThat(name("\"{\\\"name\\\":\\\"Dosa\\\"}\"")).isEqualTo("Dosa")
    }

    @Test fun truncatedJsonGivesNull() {
        assertThat(JsonExtract.extractObject("{\"name\":\"Chole\",\"kcal\":")).isNull()
    }

    @Test fun requiredKeysFromSchema() {
        assertThat(JsonExtract.requiredKeys(AiPrompts.FOOD_SCHEMA))
            .containsAtLeast("name", "kcal", "protein_g", "components")
        assertThat(JsonExtract.requiredKeys(AiPrompts.LABEL_SCHEMA)).contains("basis")
    }

    @Test fun balancedObjectsFindsEachTopLevelObject() {
        val spans = JsonExtract.balancedObjects("a {\"x\":1} b {\"y\":{\"z\":2}} c")
        assertThat(spans).containsExactly("{\"x\":1}", "{\"y\":{\"z\":2}}").inOrder()
    }
}
