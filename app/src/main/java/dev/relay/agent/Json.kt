package dev.relay.agent

import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.copy(): JSONObject = JSONObject(toString())

/** Shallow merge: keys of [override] win. */
fun JSONObject.mergedWith(override: JSONObject): JSONObject {
    val out = copy()
    override.keys().forEach { k -> out.put(k, override.get(k)) }
    return out
}

fun normalizeUrl(url: String): String =
    url.substringBefore('#').substringBefore('?').trimEnd('/')
