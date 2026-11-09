package dev.relay.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    private val builtin = JSONObject(
        """{"id":"chatgpt","name":"ChatGPT","url":"https://chatgpt.com/","desktop":false,
            "selectors":{"input":"#prompt","send":"button.send","response":"div.answer"}}""",
    )

    private fun JSONObject.keySet() = keys().asSequence().toSet()

    @Test
    fun partialSelectorOverrideKeepsTheOtherBuiltInSelectors() {
        val merged = builtin.mergedWith(JSONObject("""{"selectors":{"send":"button.go"}}"""))
        val sel = merged.getJSONObject("selectors")
        assertEquals("#prompt", sel.getString("input"))
        assertEquals("button.go", sel.getString("send"))
        assertEquals("div.answer", sel.getString("response"))
        assertEquals("ChatGPT", merged.getString("name"))
    }

    @Test
    fun diffKeepsOnlyWhatTheUserChanged() {
        val edited = JSONObject(
            """{"id":"chatgpt","name":"ChatGPT","url":"https://chatgpt.com/","desktop":true,
                "selectors":{"input":"#prompt","send":"","response":"div.answer"}}""",
        )
        val diff = edited.diffFrom(builtin)
        assertEquals(setOf("desktop", "selectors"), diff.keySet())
        assertTrue(diff.getBoolean("desktop"))
        assertEquals(setOf("send"), diff.getJSONObject("selectors").keySet())
        assertEquals("", diff.getJSONObject("selectors").getString("send"))
    }

    @Test
    fun laterBuiltInUpdatesStillApplyUnderAnOverride() {
        val diff = JSONObject("""{"id":"chatgpt","selectors":{"send":"button.go"}}""").diffFrom(builtin)
        val updated = JSONObject(builtin.toString()).put("url", "https://chatgpt.com/new")
            .put("selectors", JSONObject("""{"input":"#prompt-v2","send":"button.send","response":"div.answer"}"""))
        val merged = updated.mergedWith(diff)
        assertEquals("https://chatgpt.com/new", merged.getString("url"))
        assertEquals("#prompt-v2", merged.getJSONObject("selectors").getString("input"))
        assertEquals("button.go", merged.getJSONObject("selectors").getString("send"))
    }

    @Test
    fun unchangedEditProducesAnEmptyDiff() {
        assertEquals(0, JSONObject(builtin.toString()).diffFrom(builtin).length())
    }
}
