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

    fun chat(key: String, msgs: JSONArray, tools: JSONArray?, maxTokens: Int = 500): JSONObject {
        for (i in 0 until 6) {
            if (brain.isEmpty()) load(key)
            val m = brain
            if (m.isEmpty()) throw Exception("No Groq chat model is available for this key")
            try {
                val b = JSONObject().put("model", m).put("messages", msgs).put("temperature", 0.3).put("max_tokens", maxTokens)
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

    fun cleanNotes(s: String) = s.replace(Regex("[*`#]"), "").trim()

    /** Ask a vision model about one or more photos (JPEG bytes), e.g. pages of notes. */
    fun vision(key: String, prompt: String, jpegs: List<ByteArray>, maxTokens: Int = 1800): String {
        if (models.isEmpty()) load(key)
        val vm = models.firstOrNull { it !in bad && Regex("llama-4|scout|maverick|vision", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?: throw Exception("No vision model is available on this Groq key")
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        for (b in jpegs) content.put(JSONObject().put("type", "image_url").put("image_url",
            JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP))))
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val b = JSONObject().put("model", vm).put("messages", msgs).put("max_tokens", maxTokens).put("temperature", 0.2)
        return cleanNotes(txt(http("POST", "/chat/completions", key, b).getJSONArray("choices").getJSONObject(0).getJSONObject("message")))
    }

    /** Text-only fallback: analyse notes that were read by offline OCR. */
    fun notesFromText(key: String, ocr: String, task: String): String {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content",
            "Here is text read by OCR from photos of the user's notes (it may contain recognition errors):\n<<<\n$ocr\n>>>\n$task"))
        return cleanNotes(txt(chat(key, msgs, null, 1500)))
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

    data class Research(val text: String, val refs: List<Pair<String, String>>)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun googleRef(q: String) = "Search this on Google" to "https://www.google.com/search?q=" + enc(q)

    private fun getText(url: String, ua: String = "OMEN/1.0 (personal Android assistant)"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", ua); c.connectTimeout = 15000; c.readTimeout = 20000
        return c.inputStream.bufferedReader().readText()
    }

    private fun refsFrom(msg: JSONObject): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val ex = msg.optJSONArray("executed_tools") ?: return out
        for (i in 0 until ex.length()) {
            val res = ex.optJSONObject(i)?.optJSONObject("search_results")?.optJSONArray("results") ?: continue
            for (k in 0 until res.length()) {
                val o = res.optJSONObject(k) ?: continue
                val u = o.optString("url")
                if (u.startsWith("http") && out.none { it.second == u }) out.add(o.optString("title").ifBlank { u } to u)
            }
        }
        return out.take(5)
    }

    /** Web research with source links. Groq search models first; Wikipedia + chat model as a fallback. */
    fun researchRefs(key: String, q: String): Research {
        if (models.isEmpty()) load(key)
        val now = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You are OMEN, a calm shadowy research assistant. Right now it is $now. Search the web, then answer in plain spoken text under 120 words. No markdown, lists or links."))
            .put(JSONObject().put("role", "user").put("content", q))
        val tries = mutableListOf<Pair<String, Boolean>>()
        listOf("groq/compound", "groq/compound-mini").filter { it in models }.forEach { tries.add(it to false) }
        listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b").filter { it in models }.forEach { tries.add(it to true) }
        for ((m, bs) in tries) {
            try {
                val b = JSONObject().put("model", m).put("messages", msgs).put("temperature", 0.4).put("max_tokens", if (bs) 2000 else 900)
                if (bs) b.put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
                val msg = http("POST", "/chat/completions", key, b).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                val r = txt(msg)
                if (r.isNotBlank()) return Research(clean(r), refsFrom(msg) + googleRef(q))
            } catch (e: Exception) {}
        }
        return wikiFallback(key, q, msgs)
    }

    private fun wikiFallback(key: String, q: String, base: JSONArray): Research {
        val ctx = StringBuilder(); val refs = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONObject(getText("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=" + enc(q))).getJSONObject("query").getJSONArray("search")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val title = o.getString("title")
                ctx.append("[${i + 1}] $title: ${o.getString("snippet").replace(Regex("<[^>]+>"), "")}\n")
                refs.add(title to "https://en.wikipedia.org/wiki/" + enc(title.replace(' ', '_')))
            }
            if (arr.length() > 0) {
                val top = arr.getJSONObject(0).getString("title")
                val pg = JSONObject(getText("https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exchars=900&format=json&titles=" + enc(top)))
                    .getJSONObject("query").getJSONObject("pages")
                val first = pg.getJSONObject(pg.keys().next()).optString("extract")
                if (first.isNotBlank()) ctx.append("\nIntro of $top: $first\n")
            }
        } catch (e: Exception) {}
        val msgs = JSONArray().put(base.getJSONObject(0)).put(JSONObject().put("role", "user").put("content",
            "Question: $q\n\nSources:\n$ctx\nAnswer briefly using these sources if relevant, otherwise from your own knowledge."))
        val a = clean(txt(chat(key, msgs, null, 500))) + " Live web search is not enabled on your Groq account, so this used Wikipedia and my own knowledge."
        return Research(a, refs + googleRef(q))
    }

    /** Download a web page and answer a question about it. */
    fun askAboutPage(key: String, url: String, task: String): Research {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) OMEN/1.0")
        val type = c.contentType ?: ""
        if (type.isNotEmpty() && !type.startsWith("text/")) throw Exception("That link is not a web page I can read ($type)")
        val buf = ByteArray(700_000); var n = 0
        c.inputStream.use { ins -> while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r < 0) break; n += r } }
        var t = String(buf, 0, n, Charsets.UTF_8)
        val opt = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        t = t.replace(Regex("<script.*?</script>", opt), " ").replace(Regex("<style.*?</style>", opt), " ").replace(Regex("<!--.*?-->", opt), " ")
            .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ").trim().take(14000)
        if (t.length < 80) throw Exception("The page had no readable text")
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content",
            "Text of the web page $url:\n<<<\n$t\n>>>\nTask: $task\nAnswer in plain text under 150 words, using only this page."))
        return Research(clean(txt(chat(key, msgs, null, 700))), listOf(url to url))
    }

    /** Free AI image generation (Pollinations). Returns the saved file path. */
    fun generateImage(c: Context, prompt: String): String {
        val url = "https://image.pollinations.ai/prompt/" + enc(prompt).replace("+", "%20") + "?width=1024&height=1024&nologo=true&seed=" + (System.currentTimeMillis() % 100000)
        val con = URL(url).openConnection() as HttpURLConnection
        con.setRequestProperty("User-Agent", "OMEN/1.0"); con.connectTimeout = 20000; con.readTimeout = 120000
        if (con.responseCode !in 200..299 || !(con.contentType ?: "").startsWith("image")) throw Exception("The image service is busy. Try again in a moment")
        val dir = File(c.filesDir, "img"); dir.mkdirs()
        val f = File(dir, "gen${System.currentTimeMillis()}.jpg")
        con.inputStream.use { ins -> f.outputStream().use { ins.copyTo(it) } }
        if (f.length() < 5000) { f.delete(); throw Exception("The image service returned nothing") }
        return f.path
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
        .put(tool("generate_image", "Create or draw a new AI image from a description.", Triple("prompt", "string", "detailed description of the image")))
        .put(tool("read_website", "Open a web page link and answer a question about it.", Triple("url", "string", "the full http(s) link"), Triple("question", "string", "what to find out, or 'summarize'")))
        .put(tool("research", "Search the web for any factual, current or news question.", Triple("query", "string", "the question")))
}
