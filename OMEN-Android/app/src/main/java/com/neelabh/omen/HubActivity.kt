package com.neelabh.omen

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Command center: one glowing tile per dashboard. */
class HubActivity : DashActivity() {
    private class Tile(val icon: String, val title: String, val desc: String, val color: Int, val cls: Class<*>)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); build("Dashboards")
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        hero.addView(OmenView(this).apply { compact = true }, lp(dp(150), dp(150)))
        hero.addView(TextView(this).apply { text = "C O M M A N D   C E N T E R"; textSize = 14f; letterSpacing = 0.2f; setTextColor(Ui.ACC); gravity = Gravity.CENTER })
        hero.addView(TextView(this).apply { text = "Research, molecules, voice and images"; textSize = 12f; setTextColor(Ui.MUT); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        body.addView(hero, lp(MATCH, WRAP).apply { bottomMargin = dp(14) })

        val tiles = listOf(
            Tile("🧪", "Pharma Research", "Papers, trials, labels, targets", 0xFFFF7A90.toInt(), PharmaDashActivity::class.java),
            Tile("💊", "AI Drug Discovery", "Target to ranked hits and docking", 0xFFB05CFF.toInt(), DiscoveryActivity::class.java),
            Tile("🧬", "Structure Viewer", "2D, rotating 3D, ADMET", 0xFF2CC9B5.toInt(), MoleculeActivity::class.java),
            Tile("⚖️", "Drug Comparison", "Side by side, 2 to 4 drugs", 0xFFFFC14D.toInt(), CompareActivity::class.java),
            Tile("📊", "Trial Analytics", "Phases, status, sponsors", 0xFF4DA6FF.toInt(), TrialsActivity::class.java),
            Tile("🎙️", "Voice Control", "Wake word, log, mic test", 0xFF7CFFB2.toInt(), VoiceActivity::class.java),
            Tile("🖼️", "Image Gallery", "Created, molecules, photos", 0xFFFF6FB5.toInt(), GalleryActivity::class.java),
            Tile("🕘", "History", "Every question and answer", 0xFF9AA8FF.toInt(), HistoryActivity::class.java),
            Tile("⚙️", "Settings", "Key, voice, permissions", 0xFFB8B8C8.toInt(), SettingsActivity::class.java))
        for (pair in tiles.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (t in pair) row.addView(tile(t), lp(0, WRAP).apply { weight = 1f; setMargins(dp(5), dp(5), dp(5), dp(5)) })
            if (pair.size == 1) row.addView(View(this), lp(0, 1).apply { weight = 1f })
            body.addView(row)
        }
    }

    private fun tile(t: Tile): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); minimumHeight = dp(128); isClickable = true
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.alpha(t.color, 0x66), 0xCC101018.toInt())).apply {
            cornerRadius = dp(22).toFloat(); setStroke(dp(1), Ui.alpha(t.color, 0xAA))
        }
        addView(TextView(context).apply { text = t.icon; textSize = 32f })
        addView(TextView(context).apply { text = t.title; textSize = 15f; setTextColor(Ui.TXT); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(10), 0, dp(2)) })
        addView(TextView(context).apply { text = t.desc; textSize = 11f; setTextColor(Ui.MUT) })
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
        setOnClickListener { startActivity(Intent(this@HubActivity, t.cls)) }
    }
}
