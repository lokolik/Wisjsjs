package dev.relay.agent

import org.json.JSONObject

/** Keys that identify one conversation of a service (used to remember where the tool preamble was sent). */
object ChatKeys {
    fun forUrl(adapter: JSONObject, url: String): String {
        val id = adapter.optString("id")
        if (adapter.optString("kind") == "app") return "$id|app"
        return "$id|" + normalizeUrl(url)
    }

    /**
     * True for the "new chat" page of a multi-chat site. Every future conversation starts at that address,
     * so it must never be remembered permanently as "preamble already sent".
     */
    fun isFresh(adapter: JSONObject, key: String): Boolean {
        if (adapter.optString("kind") == "app" || adapter.optBoolean("singleThread")) return false
        return listOf(adapter.optString("newChatUrl"), adapter.optString("url"))
            .filter { it.isNotBlank() }
            .any { forUrl(adapter, it) == key }
    }
}
