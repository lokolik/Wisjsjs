package dev.relay.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatKeysTest {
    private val chatgpt = JSONObject("""{"id":"chatgpt","kind":"web","url":"https://chatgpt.com/","newChatUrl":"https://chatgpt.com/"}""")
    private val claude = JSONObject("""{"id":"claude","kind":"web","url":"https://claude.ai/new","newChatUrl":"https://claude.ai/new"}""")
    private val hark = JSONObject("""{"id":"hark","kind":"web","url":"https://hark.example/chat","singleThread":true}""")
    private val app = JSONObject("""{"id":"app-hark","kind":"app","package":"com.hark.android"}""")

    @Test
    fun keysIgnoreQueryFragmentAndTrailingSlash() {
        assertEquals("chatgpt|https://chatgpt.com/c/42", ChatKeys.forUrl(chatgpt, "https://chatgpt.com/c/42/?model=x#end"))
        assertEquals("app-hark|app", ChatKeys.forUrl(app, "anything"))
    }

    @Test
    fun newChatPagesAreFresh() {
        assertTrue(ChatKeys.isFresh(chatgpt, ChatKeys.forUrl(chatgpt, "https://chatgpt.com/")))
        assertTrue(ChatKeys.isFresh(claude, ChatKeys.forUrl(claude, "https://claude.ai/new?x=1")))
        assertFalse(ChatKeys.isFresh(chatgpt, ChatKeys.forUrl(chatgpt, "https://chatgpt.com/c/42")))
        assertFalse(ChatKeys.isFresh(claude, ChatKeys.forUrl(chatgpt, "https://chatgpt.com/")))
    }

    @Test
    fun singleThreadSitesAndAppsAreNeverFresh() {
        // These have exactly one conversation, so remembering the preamble permanently is correct.
        assertFalse(ChatKeys.isFresh(hark, ChatKeys.forUrl(hark, "https://hark.example/chat")))
        assertFalse(ChatKeys.isFresh(app, ChatKeys.forUrl(app, "")))
    }
}
