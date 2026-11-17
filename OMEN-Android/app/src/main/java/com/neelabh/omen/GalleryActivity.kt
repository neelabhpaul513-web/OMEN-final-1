package com.neelabh.omen

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** Image gallery: generated images, molecule pictures and photos, with the prompt behind each image. */
class GalleryActivity : DashActivity() {
    class Item(val path: String, val prompt: String, val t: Long)
    private var tab = "Created"
    private var items = listOf<Item>()
    private lateinit var grid: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var prompt: android.widget.EditText
    private lateinit var status: android.widget.TextView
    private val cache = LruCache<String, Bitmap>(60)
    private val pool = Executors.newFixedThreadPool(3)

    private fun decode(path: String, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun ls(d: File): List<File> = d.listFiles()?.toList() ?: emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); build("Image Gallery")
        val c = card(); section(c, "Create an image")
        prompt = input(c, "Describe the image (e.g. a glowing hooded figure in a black storm)")
        status = text(c, "", 12f, Ui.MUT)
        button(c, "Create image") { create(prompt.text.toString().trim()) }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(chips, lp(WRAP, WRAP).apply { bottomMargin = dp(10) })
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(grid, lp(MATCH, WRAP))
        drawChips(); load()
    }

    private fun drawChips() {
        chips.removeAllViews()
        for (t in listOf("Created", "Molecules", "Photos")) chips.addView(chip(t, tab == t) { tab = t; drawChips(); load() }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
    }

    private fun create(p: String) {
        if (p.isEmpty()) { toast("Describe the image first"); return }
        status.text = "Creating… this can take up to a minute."
        bg {
            val path = Brain.generateImage(this, p); GalleryStore.add(this, path, p)
            ui { status.text = "Done."; tab = "Created"; drawChips(); load() }
        }
    }

    private fun load() {
        val meta = GalleryStore.all(this)
        val img = File(filesDir, "img"); val photos = File(filesDir, "chat_images")
        val files = when (tab) {
            "Created" -> ls(img).filter { it.name.startsWith("gen") }
            "Molecules" -> ls(img).filter { it.name.startsWith("mol") }
            else -> ls(photos) + ls(img).filter { it.name.startsWith("p") }
        }
        items = files.sortedByDescending { it.lastModified() }.map { Item(it.path, meta[it.path]?.prompt ?: "", it.lastModified()) }
        render()
    }

    private fun render() {
        grid.removeAllViews()
        if (items.isEmpty()) { text(grid, "Nothing here yet.", 14f, Ui.MUT); return }
        items.chunked(3).forEach { row ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (item in row) {
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Ui.CARD); setOnClickListener { view(item) } }
                r.addView(iv, lp(0, dp(110)).apply { weight = 1f; setMargins(dp(3), dp(3), dp(3), dp(3)) })
                val cached = cache.get(item.path)
                if (cached != null) iv.setImageBitmap(cached)
                else pool.submit { val bmp = decode(item.path, 300); if (bmp != null) { cache.put(item.path, bmp); runOnUiThread { iv.setImageBitmap(bmp) } } }
            }
            repeat(3 - row.size) { r.addView(View(this), lp(0, dp(110)).apply { weight = 1f }) }
            grid.addView(r)
        }
    }

    private fun view(item: Item) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(4)) }
        box.addView(ImageView(this).apply { setImageBitmap(decode(item.path, 1200)); adjustViewBounds = true }, lp(MATCH, WRAP))
        val when_ = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.t))
        text(box, (if (item.prompt.isNotEmpty()) item.prompt + "\n" else "") + when_, 13f, Ui.TXT)
        val d = android.app.AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save to gallery") { _, _ -> save(item.path) }
            .setNeutralButton("Share") { _, _ -> share(item.path) }
            .setNegativeButton("Delete") { _, _ -> File(item.path).delete(); GalleryStore.remove(this, item.path); load() }.create()
        if (item.prompt.isNotEmpty()) button(box, "Use this prompt again") { prompt.setText(item.prompt); d.dismiss() }
        d.show()
    }

    private fun save(path: String) {
        if (Build.VERSION.SDK_INT < 29) { toast("Saving needs Android 10 or newer"); return }
        try {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "OMEN_" + System.currentTimeMillis() + ".jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OMEN")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw Exception("insert")
            contentResolver.openOutputStream(uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } }
            toast("Saved to Pictures/OMEN")
        } catch (e: Exception) { toast("Could not save the image") }
    }

    private fun share(path: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share image"))
        } catch (e: Exception) { toast("Could not share this image") }
    }

    override fun onDestroy() { super.onDestroy(); pool.shutdownNow() }
}
