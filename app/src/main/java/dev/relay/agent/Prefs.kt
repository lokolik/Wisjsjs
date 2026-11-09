package dev.relay.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("relay", Context.MODE_PRIVATE)

    /** ask | read | all */
    var approvalMode: String
        get() = sp.getString("approvalMode", "ask") ?: "ask"
        set(v) = sp.edit().putString("approvalMode", v).apply()

    /** auto (root only as a fallback / on request) | off */
    var rootMode: String
        get() = sp.getString("rootMode", "auto") ?: "auto"
        set(v) = sp.edit().putString("rootMode", v).apply()

    var autoPreamble: Boolean
        get() = sp.getBoolean("autoPreamble", true)
        set(v) = sp.edit().putBoolean("autoPreamble", v).apply()

    var maxSteps: Int
        get() = sp.getInt("maxSteps", 12)
        set(v) = sp.edit().putInt("maxSteps", v.coerceIn(1, 50)).apply()

    var mcpEnabled: Boolean
        get() = sp.getBoolean("mcpEnabled", false)
        set(v) = sp.edit().putBoolean("mcpEnabled", v).apply()

    var mcpPort: Int
        get() = sp.getInt("mcpPort", 8765)
        set(v) = sp.edit().putInt("mcpPort", v).apply()

    val mcpToken: String
        get() = sp.getString("mcpToken", null) ?: newToken().also { sp.edit().putString("mcpToken", it).apply() }

    fun rotateMcpToken(): String = newToken().also { sp.edit().putString("mcpToken", it).apply() }

    var lastAdapter: String?
        get() = sp.getString("lastAdapter", null)
        set(v) = sp.edit().putString("lastAdapter", v).apply()

    /** User-added sites/apps and overrides of built-in adapters (same id). */
    var customAdapters: JSONArray
        get() = try {
            JSONArray(sp.getString("customAdapters", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        set(v) = sp.edit().putString("customAdapters", v.toString()).apply()

    fun preambleSent(key: String): Boolean = sp.getStringSet("preamble", emptySet())?.contains(key) == true

    fun markPreamble(key: String) {
        val set = HashSet(sp.getStringSet("preamble", emptySet()) ?: emptySet())
        if (set.size > 2000) set.clear()
        set += key
        sp.edit().putStringSet("preamble", set).apply()
    }

    fun forgetPreamble(key: String) {
        val set = HashSet(sp.getStringSet("preamble", emptySet()) ?: emptySet())
        set -= key
        sp.edit().putStringSet("preamble", set).apply()
    }

    fun settingsJson(): JSONObject = JSONObject()
        .put("approvalMode", approvalMode)
        .put("rootMode", rootMode)
        .put("autoPreamble", autoPreamble)
        .put("maxSteps", maxSteps)
        .put("mcpEnabled", mcpEnabled)
        .put("mcpPort", mcpPort)

    private fun newToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
