package com.neelabh.omen

import android.content.Intent
import android.os.Bundle

/** One place to reach every dashboard. */
class HubActivity : DashActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); build("Dashboards")
        text(body, "Research, molecules, voice and images in one place.", 14f, Ui.MUT)
        val items = listOf<Triple<String, String, Class<*>>>(
            Triple("🧪  Pharma Research", "PubMed, trials, drug labels, molecule and targets, with live source status", PharmaDashActivity::class.java),
            Triple("💊  AI Drug Discovery", "Target to potent compounds, drug-likeness ranking, 3D structures for docking", DiscoveryActivity::class.java),
            Triple("🧬  Molecular Structure Viewer", "2D picture, rotating 3D model, properties and rule-based ADMET", MoleculeActivity::class.java),
            Triple("⚖️  Drug Comparison", "Side by side: properties, mechanism, indication, trials and papers", CompareActivity::class.java),
            Triple("📊  Clinical Trial Analytics", "Phases, status, years, sponsors and enrolment charts", TrialsActivity::class.java),
            Triple("🎙️  Voice Control", "Wake word, live log, mic test, voice settings and commands", VoiceActivity::class.java),
            Triple("🖼️  Image Gallery", "Everything you generated, scanned or photographed", GalleryActivity::class.java),
            Triple("🕘  History", "Every question and answer", HistoryActivity::class.java),
            Triple("⚙️  Settings", "Key, wake word, appearance, permissions", SettingsActivity::class.java))
        for ((t, s, cls) in items) {
            val c = card(); c.isClickable = true
            text(c, t, 18f, Ui.TXT, true); text(c, s, 13f, Ui.MUT)
            c.setOnClickListener { startActivity(Intent(this, cls)) }
        }
    }
}
