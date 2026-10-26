package com.neelabh.omen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Offline text recognition (ML Kit). Call from a background thread. */
object Ocr {
    fun read(path: String): String {
        val bmp = BitmapFactory.decodeFile(path) ?: return ""
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 40, TimeUnit.SECONDS).text } finally { rec.close() }
    }

    fun readBitmap(bmp: Bitmap): String {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 40, TimeUnit.SECONDS).text } finally { rec.close() }
    }
}
