package com.neelabh.omen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Every question, search, photo and command with its answer and time. Nothing is de-duplicated; keeps the latest 1500. */
object HistoryStore {
    private const val MAX = 1500
    data class Entry(val t: Long, val type: String, val q: String, val a: String)

    private fun f(c: Context) = File(c.filesDir, "history.json")

    @Synchronized
    private fun load(c: Context): JSONArray {
        val file = f(c)
        if (!file.exists()) {
            val arr = JSONArray()
            try {   // bring over the old short list the first time
                val old = JSONArray(c.getSharedPreferences("omen", Context.MODE_PRIVATE).getString("history", "[]"))
                for (i in 0 until old.length()) arr.put(JSONObject().put("t", 0L).put("ty", "Chat").put("q", old.getString(i)).put("a", ""))
            } catch (e: Exception) {}
            return arr
        }
        return try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
    }

    private fun save(c: Context, a: JSONArray) {
        val out = JSONArray()
        for (i in maxOf(0, a.length() - MAX) until a.length()) out.put(a.get(i))
        try { f(c).writeText(out.toString()) } catch (e: Exception) {}
    }

    fun typeOf(q: String): String {
        val t = q.lowercase().trim()
        return when {
            Regex("^(?:pharma(?:ceutical)? research|medical research|full research|research|literature review|review)\\b").containsMatchIn(t) -> "Pharma research"
            Regex("^(?:show|find|get|search|give|display)(?:\\s+me)?(?:\\s+(?:some|a few))?\\s+(?:images?|pictures?|photos?|pics?)").containsMatchIn(t) -> "Image search"
            Regex("^(?:generate|create|make|draw|paint|render|design)\\b.*\\b(image|picture|logo|poster|wallpaper|illustration|artwork|drawing|painting)|^(imagine|draw|paint)\\s").containsMatchIn(t) -> "Image creation"
            Regex("^(?:search for|search|look up|google|find out|tell me about)\\b").containsMatchIn(t) || t.endsWith("?") ||
                Regex("^(who|what|when|where|why|how|which|latest|news|weather|price|score|define)\\b").containsMatchIn(t) -> "Web search"
            Regex("^(open|launch|call|dial|set|timer|alarm|message|whatsapp|text|play|navigate|pause|resume|next|flashlight|torch|volume|mute|take a note|note)\\b").containsMatchIn(t) -> "Command"
            else -> "Chat"
        }
    }

    @Synchronized
    fun addQuery(c: Context, raw: String, forceType: String? = null) {
        var q = raw; var type = forceType ?: typeOf(raw)
        if (raw.startsWith("\u0001")) {
            val end = raw.indexOf('\u0001', 1); if (end < 1) return
            val head = raw.substring(1, end); val cap = raw.substring(end + 1).trim()
            type = when { head.startsWith("DOC:") -> "Document"; cap == "Search this photo" -> "Photo search"; else -> "Photo" }
            q = if (head.startsWith("DOC:")) head.removePrefix("DOC:") + (if (cap.isNotEmpty()) " · $cap" else "") else cap.ifEmpty { "[photo]" }
        }
        if (q.isBlank()) return
        val a = load(c)
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("ty", type).put("q", q.take(500)).put("a", ""))
        save(c, a)
    }

    /** Attaches OMEN's reply to the newest question that has no answer yet. */
    @Synchronized
    fun setAnswer(c: Context, text: String) {
        if (text == "Searching the web…" || text == "Reading the page…" || text == "Searching images…" || text.startsWith("Creating your image") || text.startsWith("Researching the literature") || text.startsWith("Running the molecular")) return
        val a = load(c); if (a.length() == 0) return
        val last = a.getJSONObject(a.length() - 1)
        if (last.optString("a").isNotEmpty() || System.currentTimeMillis() - last.optLong("t") > 10 * 60_000L) return
        if (text.startsWith("\u0001") && last.optString("ty") == "Pharma research") return
        val ans = if (text.startsWith("\u0001")) "[images] " + text.substringAfter('\u0001').substringAfter('\u0001') else text
        last.put("a", ans.take(6000)); save(c, a)
    }

    @Synchronized
    fun retag(c: Context, type: String) {
        val a = load(c); if (a.length() == 0) return
        a.getJSONObject(a.length() - 1).put("ty", type); save(c, a)
    }

    @Synchronized
    fun all(c: Context): List<Entry> {
        val a = load(c)
        return (a.length() - 1 downTo 0).map { val o = a.getJSONObject(it); Entry(o.optLong("t"), o.optString("ty", "Chat"), o.optString("q"), o.optString("a")) }
    }

    @Synchronized
    fun delete(c: Context, e: Entry) {
        val a = load(c); val out = JSONArray(); var removed = false
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (!removed && o.optLong("t") == e.t && o.optString("q") == e.q) { removed = true; continue }
            out.put(o)
        }
        save(c, out)
    }

    @Synchronized
    fun clear(c: Context) {
        try { f(c).delete() } catch (e: Exception) {}
        c.getSharedPreferences("omen", Context.MODE_PRIVATE).edit().remove("history").apply()
    }
}
