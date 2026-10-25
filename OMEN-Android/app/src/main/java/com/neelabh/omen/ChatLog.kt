package com.neelabh.omen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent chat transcript plus a separate, long-lived search/query history. */
object ChatLog {
    private const val MAX = 60
    @Volatile var version = 0

    @Synchronized
    fun add(c: Context, user: Boolean, text: String) {
        val p = c.getSharedPreferences("omen", Context.MODE_PRIVATE)
        val a = try { JSONArray(p.getString("chat", "[]")) } catch (e: Exception) { JSONArray() }
        a.put(JSONObject().put("u", user).put("t", text))
        val out = JSONArray()
        for (i in maxOf(0, a.length() - MAX) until a.length()) out.put(a.get(i))
        p.edit().putString("chat", out.toString()).apply()
        version++
        if (user) QueryHistory.add(c, text)
    }

    @Synchronized
    fun all(c: Context): List<Pair<Boolean, String>> {
        val p = c.getSharedPreferences("omen", Context.MODE_PRIVATE)
        val a = try { JSONArray(p.getString("chat", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getBoolean("u") to o.getString("t") } }
    }

    /** Clears only the on-screen chat. Search history is kept. */
    @Synchronized
    fun clear(c: Context) { c.getSharedPreferences("omen", Context.MODE_PRIVATE).edit().remove("chat").apply(); version++ }
}

object QueryHistory {
    private const val MAX = 200

    @Synchronized
    fun add(c: Context, q: String) {
        val t = q.trim(); if (t.isEmpty()) return
        val p = c.getSharedPreferences("omen", Context.MODE_PRIVATE)
        val list = load(p.getString("history", "[]")).filter { !it.equals(t, ignoreCase = true) }.toMutableList()
        list.add(t)
        p.edit().putString("history", JSONArray(list.takeLast(MAX)).toString()).apply()
    }

    /** Newest first. */
    @Synchronized
    fun all(c: Context): List<String> = load(c.getSharedPreferences("omen", Context.MODE_PRIVATE).getString("history", "[]")).reversed()

    @Synchronized
    fun clear(c: Context) { c.getSharedPreferences("omen", Context.MODE_PRIVATE).edit().remove("history").apply() }

    private fun load(s: String?): List<String> = try {
        val a = JSONArray(s ?: "[]"); (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }
}
