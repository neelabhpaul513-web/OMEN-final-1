package com.neelabh.omen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Remembers the prompt behind every generated image for the gallery. */
object GalleryStore {
    class Item(val path: String, val prompt: String, val t: Long)
    private fun f(c: Context) = File(c.filesDir, "gallery.json")

    @Synchronized private fun load(c: Context): JSONArray = try { JSONArray(f(c).readText()) } catch (e: Exception) { JSONArray() }

    @Synchronized fun add(c: Context, path: String, prompt: String) {
        val a = load(c); a.put(JSONObject().put("p", path).put("q", prompt.take(300)).put("t", System.currentTimeMillis()))
        try { f(c).writeText(a.toString()) } catch (e: Exception) {}
    }

    @Synchronized fun all(c: Context): Map<String, Item> {
        val a = load(c); val m = LinkedHashMap<String, Item>()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); m[o.optString("p")] = Item(o.optString("p"), o.optString("q"), o.optLong("t")) }
        return m
    }

    @Synchronized fun remove(c: Context, path: String) {
        val a = load(c); val out = JSONArray()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("p") != path) out.put(o) }
        try { f(c).writeText(out.toString()) } catch (e: Exception) {}
    }
}

/** Live record of what the voice system heard and did (shown on the Voice Control dashboard). */
object VoiceLog {
    class Ev(val t: Long, val kind: String, val text: String)
    private val ev = java.util.ArrayDeque<Ev>()
    @Volatile var errors = 0
    @Volatile var lastErr = 0
    @Volatile var wakes = 0
    @Volatile var since = System.currentTimeMillis()

    @Synchronized fun add(kind: String, text: String) {
        ev.addFirst(Ev(System.currentTimeMillis(), kind, text.take(160)))
        while (ev.size > 40) ev.removeLast()
    }

    @Synchronized fun all(): List<Ev> = ev.toList()
}
