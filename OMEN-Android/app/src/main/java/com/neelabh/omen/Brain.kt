package com.neelabh.omen

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Groq backend: finds a working model on your account, tool-calling, and a web-research chain. */
object Brain {
    private const val BASE = "https://api.groq.com/openai/v1"
    @Volatile var models: List<String> = emptyList()
    @Volatile var brain = ""
    @Volatile var web = ""
    val bad = mutableSetOf<String>()
    private val pref = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b",
        "meta-llama/llama-4-maverick-17b-128e-instruct", "meta-llama/llama-4-scout-17b-16e-instruct",
        "openai/gpt-oss-20b", "qwen/qwen3-32b", "llama-3.1-8b-instant")

    private fun http(method: String, path: String, key: String, body: JSONObject?): JSONObject {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("User-Agent", "OMEN/1.0")
        if (body != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val st = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val d = JSONObject(st.bufferedReader().readText())
        if (d.has("error")) throw Exception(d.getJSONObject("error").optString("message", "Groq error"))
        return d
    }

    fun load(key: String) {
        val d = http("GET", "/models", key, null).getJSONArray("data")
        models = (0 until d.length()).map { d.getJSONObject(it).getString("id") }
        pick()
    }

    private fun pick() {
        val ok = { id: String -> id in models && id !in bad }
        brain = pref.firstOrNull(ok)
            ?: models.firstOrNull { ok(it) && !Regex("whisper|tts|guard|orpheus|embed|playai|compound|safeguard", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?: ""
        web = listOf("groq/compound", "groq/compound-mini").firstOrNull(ok) ?: ""
    }

    fun txt(m: JSONObject): String = if (m.isNull("content")) "" else m.getString("content")
    fun clean(s: String) = s.replace(Regex("[*#_`]"), "").trim()

    fun chat(key: String, msgs: JSONArray, tools: JSONArray?): JSONObject {
        for (i in 0 until 6) {
            if (brain.isEmpty()) load(key)
            val m = brain
            if (m.isEmpty()) throw Exception("No Groq chat model is available for this key")
            try {
                val b = JSONObject().put("model", m).put("messages", msgs).put("temperature", 0.3).put("max_tokens", 500)
                if (tools != null) b.put("tools", tools).put("tool_choice", "auto")
                return http("POST", "/chat/completions", key, b).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            } catch (e: Exception) {
                if (Regex("does not exist|do not have access|not found|decommission", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")) { bad.add(m); pick() }
                else throw e
            }
        }
        throw Exception("No working Groq model found")
    }

    fun research(key: String, q: String): String {
        if (models.isEmpty()) load(key)
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You are OMEN, a calm shadowy research assistant. Search the web, then answer in plain spoken text under 120 words. No markdown, lists or links."))
            .put(JSONObject().put("role", "user").put("content", q))
        val tries = mutableListOf<Pair<String, Boolean>>()
        listOf("groq/compound", "groq/compound-mini").filter { it in models }.forEach { tries.add(it to false) }
        listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b").filter { it in models }.forEach { tries.add(it to true) }
        for ((m, bs) in tries) {
            try {
                val b = JSONObject().put("model", m).put("messages", msgs).put("temperature", 0.4).put("max_tokens", if (bs) 2000 else 900)
                if (bs) b.put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
                val r = txt(http("POST", "/chat/completions", key, b).getJSONArray("choices").getJSONObject(0).getJSONObject("message"))
                if (r.isNotBlank()) return clean(r)
            } catch (e: Exception) {}
        }
        return clean(txt(chat(key, msgs, null))) + " Live search is not enabled on your Groq account, so that is from memory."
    }

    private fun tool(n: String, d: String, vararg p: Triple<String, String, String>): JSONObject {
        val props = JSONObject()
        p.forEach { props.put(it.first, JSONObject().put("type", it.second).put("description", it.third)) }
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("description", d)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(p.map { it.first }))))
    }

    val tools: JSONArray = JSONArray()
        .put(tool("set_alarm", "Set an alarm at a clock time (24-hour).", Triple("hour", "integer", "0-23"), Triple("minute", "integer", "0-59")))
        .put(tool("set_timer", "Start a countdown timer.", Triple("seconds", "integer", "duration in seconds")))
        .put(tool("open_app", "Open an installed app on the phone.", Triple("name", "string", "app name")))
        .put(tool("send_whatsapp", "Send a WhatsApp message to a contact name or phone number.", Triple("to", "string", "contact or number"), Triple("message", "string", "text")))
        .put(tool("send_sms", "Send an SMS text message.", Triple("to", "string", "contact or number"), Triple("message", "string", "text")))
        .put(tool("call", "Phone call a contact name or number.", Triple("to", "string", "contact or number")))
        .put(tool("remember", "Remember a lasting fact or preference about the user.", Triple("fact", "string", "the fact")))
        .put(tool("research", "Search the web for any factual, current or news question.", Triple("query", "string", "the question")))
}
