package dev.relay.agent

import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.copy(): JSONObject = JSONObject(toString())

/** Merge where keys of [override] win; nested objects (e.g. selectors) are merged key by key. */
fun JSONObject.mergedWith(override: JSONObject): JSONObject {
    val out = copy()
    override.keys().forEach { k ->
        val v = override.get(k)
        val cur = out.opt(k)
        out.put(k, if (v is JSONObject && cur is JSONObject) cur.mergedWith(v) else v)
    }
    return out
}

/** The keys of this object whose values differ from [base]; nested objects are compared key by key. */
fun JSONObject.diffFrom(base: JSONObject): JSONObject {
    val out = JSONObject()
    keys().forEach { k ->
        val v = get(k)
        val b = base.opt(k)
        when {
            v is JSONObject && b is JSONObject -> v.diffFrom(b).takeIf { it.length() > 0 }?.let { out.put(k, it) }
            b == null -> if (v.toString().isNotEmpty()) out.put(k, v)
            v.toString() != b.toString() -> out.put(k, v)
        }
    }
    return out
}

fun normalizeUrl(url: String): String =
    url.substringBefore('#').substringBefore('?').trimEnd('/')
