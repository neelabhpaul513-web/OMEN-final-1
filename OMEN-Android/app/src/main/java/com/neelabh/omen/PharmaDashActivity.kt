package com.neelabh.omen

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/** Pharma Research dashboard: all six sources side by side, with live status. */
class PharmaDashActivity : DashActivity() {
    private lateinit var results: LinearLayout
    private val rows = LinkedHashMap<String, TextView>()
    private var lastQ = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); build("Pharma Research")
        val c = card()
        val q = input(c, "Drug, disease or question (e.g. metformin type 2 diabetes)", intent.getStringExtra("q") ?: "")
        button(c, "Run research") { run(q.text.toString().trim()) }
        val s = card(); section(s, "Source status")
        for (n in listOf("PubMed", "ClinicalTrials.gov", "openFDA label", "DailyMed", "PubChem", "ChEMBL")) rows[n] = text(s, "…  $n", 14f, Ui.MUT)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results, lp(MATCH, WRAP))
        if (q.text.isNotBlank()) run(q.text.toString().trim())
    }

    private fun run(q: String) {
        if (q.isEmpty()) { toast("Type a drug, disease or question"); return }
        if (key().isBlank()) { toast("Add your Groq key in Settings"); return }
        lastQ = q; results.removeAllViews()
        rows.forEach { (n, t) -> t.text = "…  $n"; t.setTextColor(Ui.MUT) }
        bg {
            val g = Pharma.gather(key(), q) { name, line ->
                ui { rows[name]?.let { t -> val ok = line.contains(": OK"); t.text = (if (ok) "✔  " else "✖  ") + line.removePrefix("- "); t.setTextColor(if (ok) Ui.OK else Ui.BAD) } }
            }
            ui { show(g) }
        }
    }

    private fun show(g: Pharma.Gathered) {
        if (g.papers.isNotEmpty()) {
            val c = card(results); section(c, "Evidence mix")
            c.addView(BarChartView(this, Pharma.evidenceMix(g.papers)), lp(MATCH, WRAP))
        }
        if (g.papers.isNotEmpty()) {
            val c = card(results); section(c, "Key papers (tap to open PubMed)")
            for (p in g.papers) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)); isClickable = true; setOnClickListener { openUrl("https://pubmed.ncbi.nlm.nih.gov/${p.pmid}/") } }
                text(r, p.title, 14f, Ui.TXT, true)
                text(r, Pharma.evidenceLevel(p) + " · " + p.journal + " " + p.year + " · PMID " + p.pmid, 12f, Ui.ACC)
                c.addView(r)
            }
        }
        if (g.trials.isNotEmpty()) {
            val c = card(results); section(c, "Clinical trials (tap to open)")
            for (t in g.trials) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)); isClickable = true; setOnClickListener { openUrl("https://clinicaltrials.gov/study/${t.nct}") } }
                text(r, t.title, 14f, Ui.TXT, true)
                text(r, t.nct + " · " + t.phase.ifBlank { "phase n/a" } + " · " + t.status + " · enrolment " + t.enroll + " · " + t.sponsor, 12f, Ui.ACC)
                if (t.primary.isNotBlank()) text(r, "Primary outcome: " + t.primary, 12f, Ui.MUT)
                if (t.results.isNotBlank()) text(r, "Posted result: " + t.results, 12f, Ui.OK)
                c.addView(r)
            }
        }
        g.label?.let { l ->
            val c = card(results); section(c, "Drug label (openFDA)")
            text(c, l.names + " · effective " + l.effective, 14f, Ui.TXT, true); text(c, l.text.take(1800), 13f, Ui.MUT)
        }
        if (g.dm.isNotEmpty()) {
            val c = card(results); section(c, "DailyMed official labels (tap to open)")
            for (d in g.dm) text(c, d.title.take(90) + " · " + d.date, 13f, Ui.ACC).setOnClickListener { openUrl("https://dailymed.nlm.nih.gov/dailymed/lookup.cfm?setid=${d.setId}") }
        }
        g.mol?.let { m ->
            val c = card(results); section(c, "Molecule (PubChem)")
            text(c, Pharma.molText(m), 13f, Ui.TXT)
            button(c, "Open in structure viewer") { startActivity(Intent(this, MoleculeActivity::class.java).putExtra("name", g.drug)) }
        }
        if (g.mech.isNotEmpty() || g.acts.isNotEmpty()) {
            val c = card(results); section(c, "Targets and potency (ChEMBL)")
            g.mech.forEach { text(c, "• " + it.action.lowercase() + " of " + it.target + ": " + it.moa, 13f, Ui.TXT) }
            g.acts.forEach { text(c, "• " + it.type + " " + Pharma.potency(it.value) + " against " + it.target, 13f, Ui.ACC) }
        }
        val brief = card(results)
        button(brief, "Write the full cited AI brief") {
            toast("Writing the brief…")
            bg { val o = Pharma.research(key(), lastQ); ui { showText("Research brief", o.text + "\n\nREFERENCES\n" + o.refs.mapIndexed { i, p -> "${i + 1}. ${p.first}\n${p.second}" }.joinToString("\n")) } }
        }
    }
}
