package com.neelabh.omen

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads the small offline English speech model used only to spot "Hey Omen". */
object WakeModel {
    private const val ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    fun dir(c: Context) = File(c.filesDir, "vosk-model")
    fun ready(c: Context) = File(dir(c), "am/final.mdl").exists()

    fun download(c: Context, progress: (Int) -> Unit) {
        val tmp = File(c.cacheDir, "vosk.zip")
        val con = URL(ZIP_URL).openConnection() as HttpURLConnection
        con.connectTimeout = 20000; con.readTimeout = 60000
        val total = con.contentLength.toLong()
        con.inputStream.use { ins ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(65536); var done = 0L; var last = -1
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) { val p = (done * 90 / total).toInt(); if (p != last) { last = p; progress(p) } }
                }
            }
        }
        val dest = dir(c); dest.deleteRecursively(); dest.mkdirs()
        ZipInputStream(tmp.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val rel = e.name.substringAfter('/', "")
                if (rel.isNotEmpty()) {
                    val f = File(dest, rel)
                    if (f.canonicalPath.startsWith(dest.canonicalPath)) {
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    }
                }
                e = z.nextEntry
            }
        }
        tmp.delete(); progress(100)
    }
}

/** Always-on, offline "Hey Omen" detector (Vosk, restricted to a tiny grammar). Frees the mic the moment it hears the phrase. */
class WakeEngine(private val c: Context, private val onWake: () -> Unit, private val onFail: () -> Unit) {
    @Volatile private var running = false
    @Volatile private var gen = 0
    private var model: Model? = null

    fun start() {
        if (running) return
        running = true; gen++
        val g = gen
        Thread { loop(g) }.start()
    }

    fun stop() { running = false; gen++ }

    fun close() { stop(); try { model?.close() } catch (e: Throwable) {}; model = null }

    @SuppressLint("MissingPermission")
    private fun loop(g: Int) {
        var ar: AudioRecord? = null
        var rec: Recognizer? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val m = model ?: Model(WakeModel.dir(c).path).also { model = it }
            rec = Recognizer(m, 16000.0f, "[\"hey omen\", \"omen\", \"hey oman\", \"oman\", \"[unk]\"]")
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
            if (ar.state != AudioRecord.STATE_INITIALIZED) throw Exception("mic busy")
            ar.startRecording()
            val buf = ShortArray(2000)
            var heard = false
            while (running && g == gen) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                val json = if (rec.acceptWaveForm(buf, n)) rec.result else rec.partialResult
                if (json.contains("omen") || json.contains("oman")) { heard = true; break }
            }
            try { ar.stop() } catch (e: Throwable) {}
            ar.release(); ar = null
            rec.close(); rec = null
            if (heard && g == gen) { running = false; onWake() }
        } catch (e: Throwable) {
            try { ar?.release() } catch (x: Throwable) {}
            try { rec?.close() } catch (x: Throwable) {}
            if (g == gen) { running = false; onFail() }
        }
    }
}
