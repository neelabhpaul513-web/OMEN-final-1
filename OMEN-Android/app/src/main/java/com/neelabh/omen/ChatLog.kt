package com.neelabh.omen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent chat history shared by the screen and the background service. */
object ChatLog {
    private const val MAX = 60

    @Synchronized
    fun add(c: Context, user: Boolean, text: String) {
        val p = c.getSharedPreferences("omen", Context.MODE_PRIVATE)
        val a = try { JSONArray(p.getString("chat", "[]")) } catch (e: Exception) { JSONArray() }
        a.put(JSONObject().put("u", user).put("t", text))
        val out = JSONArray()
        for (i in maxOf(0, a.length() - MAX) until a.length()) out.put(a.get(i))
        p.edit().putString("chat", out.toString()).apply()
    }

    @Synchronized
    fun all(c: Context): List<Pair<Boolean, String>> {
        val p = c.getSharedPreferences("omen", Context.MODE_PRIVATE)
        val a = try { JSONArray(p.getString("chat", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getBoolean("u") to o.getString("t") } }
    }

    @Synchronized
    fun clear(c: Context) { c.getSharedPreferences("omen", Context.MODE_PRIVATE).edit().remove("chat").apply() }
}
