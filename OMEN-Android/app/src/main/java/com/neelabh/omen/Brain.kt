package com.neelabh.omen

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
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

    /** Ask a vision model about a photo (JPEG bytes). */
    fun vision(key: String, prompt: String, jpeg: ByteArray): String {
        if (models.isEmpty()) load(key)
        val vm = models.firstOrNull { it !in bad && Regex("llama-4|scout|maverick|vision", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?: throw Exception("No vision model is available on this Groq key")
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt + " Answer briefly in plain text."))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val b = JSONObject().put("model", vm).put("messages", msgs).put("max_tokens", 700).put("temperature", 0.3)
        return clean(txt(http("POST", "/chat/completions", key, b).getJSONArray("choices").getJSONObject(0).getJSONObject("message")))
    }

    /** Free image search (Wikimedia Commons). Returns paths of downloaded thumbnails. */
    fun images(c: Context, q: String): List<String> {
        val ua = "OMEN/1.0 (personal Android assistant)"
        val api = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrlimit=10&prop=imageinfo&iiprop=url%7Cmime&iiurlwidth=600&format=json&gsrsearch=" +
            URLEncoder.encode("$q filetype:bitmap", "UTF-8")
        val con = URL(api).openConnection() as HttpURLConnection
        con.setRequestProperty("User-Agent", ua); con.connectTimeout = 15000; con.readTimeout = 20000
        val pages = JSONObject(con.inputStream.bufferedReader().readText()).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val urls = mutableListOf<String>()
        for (k in pages.keys()) {
            val ii = pages.getJSONObject(k).optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val mime = ii.optString("mime")
            if (!mime.startsWith("image/jpeg") && !mime.startsWith("image/png")) continue
            urls.add(ii.optString("thumburl").ifEmpty { ii.optString("url") })
        }
        val dir = File(c.filesDir, "img"); dir.mkdirs()
        val out = mutableListOf<String>()
        for (u in urls.take(9)) {
            try {
                val cc = URL(u).openConnection() as HttpURLConnection
                cc.setRequestProperty("User-Agent", ua); cc.connectTimeout = 10000; cc.readTimeout = 15000
                val f = File(dir, "i${System.currentTimeMillis()}_${out.size}.jpg")
                cc.inputStream.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                if (f.length() > 1000) out.add(f.path)
            } catch (e: Exception) {}
            if (out.size >= 6) break
        }
        return out
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
        .put(tool("image_search", "Show pictures or photos of something.", Triple("query", "string", "what to show pictures of")))
        .put(tool("research", "Search the web for any factual, current or news question.", Triple("query", "string", "the question")))
}
