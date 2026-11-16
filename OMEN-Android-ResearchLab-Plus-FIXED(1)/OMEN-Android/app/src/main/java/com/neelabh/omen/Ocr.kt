package com.neelabh.omen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Offline text recognition (ML Kit). Call from a background thread. */
object Ocr {
    private fun enhance(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val c = 1.7f; val t = (-0.5f * c + 0.5f) * 255f - 12f
        cm.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /** Reads a photo twice (as is, and with boosted contrast, which helps pencil and pen notes) and keeps the longer result. */
    fun read(path: String): String {
        val bmp = BitmapFactory.decodeFile(path) ?: return ""
        val plain = try { readBitmap(bmp) } catch (e: Exception) { "" }
        val boosted = try { val e = enhance(bmp); val t = readBitmap(e); e.recycle(); t } catch (e: Exception) { "" }
        if (plain.isBlank() && boosted.isBlank()) throw Exception("on-device text reader returned nothing")
        return if (boosted.length > plain.length) boosted else plain
    }

    fun readBitmap(bmp: Bitmap): String {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 40, TimeUnit.SECONDS).text } finally { rec.close() }
    }
}
