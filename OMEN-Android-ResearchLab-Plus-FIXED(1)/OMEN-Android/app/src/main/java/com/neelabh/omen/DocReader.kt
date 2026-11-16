package com.neelabh.omen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import java.util.zip.ZipInputStream

/** Pulls plain text out of documents shared with OMEN (txt, csv, md, json, pdf, docx, pptx). */
object DocReader {
    fun name(c: Context, uri: Uri): String {
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: "document"
            }
        } catch (e: Exception) {}
        return uri.lastPathSegment ?: "document"
    }

    fun read(c: Context, uri: Uri): Pair<String, String> {
        val name = name(c, uri)
        val mime = c.contentResolver.getType(uri) ?: ""
        val lower = name.lowercase()
        val text = when {
            mime == "application/pdf" || lower.endsWith(".pdf") -> pdf(c, uri)
            lower.endsWith(".docx") || mime.contains("wordprocessingml") -> office(c, uri, "</w:p>") { it == "word/document.xml" }
            lower.endsWith(".pptx") || mime.contains("presentationml") -> office(c, uri, "</a:p>") { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            mime.startsWith("text/") || Regex("\\.(txt|csv|md|json|log|xml|html?)$").containsMatchIn(lower) ->
                c.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            else -> throw Exception("Unsupported file type: $name")
        }
        if (text.isBlank()) throw Exception("No readable text in $name")
        return name to text.take(30000)
    }

    private fun pdf(c: Context, uri: Uri): String {
        val pfd = c.contentResolver.openFileDescriptor(uri, "r") ?: throw Exception("Cannot open the PDF")
        PdfRenderer(pfd).use { r ->
            val sb = StringBuilder()
            for (i in 0 until minOf(r.pageCount, 12)) {
                r.openPage(i).use { p ->
                    val s = 1500f / p.width
                    val bmp = Bitmap.createBitmap((p.width * s).toInt(), (p.height * s).toInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    sb.append("--- page ${i + 1} ---\n").append(Ocr.readBitmap(bmp)).append("\n")
                    bmp.recycle()
                }
            }
            return sb.toString()
        }
    }

    private fun office(c: Context, uri: Uri, para: String, wanted: (String) -> Boolean): String {
        val sb = StringBuilder()
        c.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (wanted(e.name)) {
                        val xml = z.readBytes().toString(Charsets.UTF_8)
                        sb.append(xml.replace(para, "\n").replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<")
                            .replace("&gt;", ">").replace("&quot;", "\"").replace(Regex("\\n\\s*\\n+"), "\n").trim()).append("\n")
                    }
                    e = z.nextEntry
                }
            }
        }
        return sb.toString()
    }
}
