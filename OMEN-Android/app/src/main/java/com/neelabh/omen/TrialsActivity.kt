package com.neelabh.omen

import android.os.Bundle
import android.widget.LinearLayout

/** Clinical trial analytics dashboard (ClinicalTrials.gov). */
class TrialsActivity : DashActivity() {
    private lateinit var out: LinearLayout
    private lateinit var chips: LinearLayout
    private var mode = "term"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); build("Clinical Trial Analytics")
        val c = card()
        text(c, "Analyses up to 100 ClinicalTrials.gov studies for a condition or drug.", 13f, Ui.MUT)
        val q = input(c, "e.g. type 2 diabetes, metformin, breast cancer")
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        c.addView(chips, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        drawChips()
        button(c, "Analyze") { run(q.text.toString().trim()) }
        out = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(out, lp(MATCH, WRAP))
    }

    private fun drawChips() {
        chips.removeAllViews()
        for ((l, m) in listOf("Any" to "term", "Condition" to "cond", "Drug" to "intr"))
            chips.addView(chip(l, mode == m) { mode = m; drawChips() }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
    }

    private fun run(q: String) {
        if (q.isEmpty()) { toast("Type a condition or drug"); return }
        out.removeAllViews(); text(out, "Loading from ClinicalTrials.gov…", 14f, Ui.MUT)
        bg { val s = Pharma.trialStats(q, mode); ui { render(s) } }
    }

    private fun render(s: Pharma.TrialStats) {
        out.removeAllViews()
        val c = card(out); section(c, "Overview")
        text(c, "Studies on ClinicalTrials.gov: " + s.total + "   (analysed: first " + s.sample + ")", 15f, Ui.TXT, true)
        val pct = if (s.sample == 0) 0 else s.withResults * 100 / s.sample
        text(c, "With posted results: " + s.withResults + " of " + s.sample + " (" + pct + "%)", 14f, Ui.OK)
        text(c, "Median enrolment: " + s.enrollMedian + "   ·   total enrolment in sample: " + s.enrollTotal, 14f, Ui.TXT)
        if (s.byPhase.isNotEmpty()) { val k = card(out); section(k, "By phase"); k.addView(BarChartView(this, s.byPhase), lp(MATCH, WRAP)) }
        if (s.byStatus.isNotEmpty()) { val k = card(out); section(k, "By status"); k.addView(BarChartView(this, s.byStatus, 0xFF7CFFB2.toInt()), lp(MATCH, WRAP)) }
        if (s.byYear.isNotEmpty()) { val k = card(out); section(k, "Studies by start year (latest 15 years in sample)"); k.addView(BarChartView(this, s.byYear, 0xFFC79CFF.toInt()), lp(MATCH, WRAP)) }
        if (s.sponsors.isNotEmpty()) { val k = card(out); section(k, "Top sponsors"); k.addView(BarChartView(this, s.sponsors, 0xFFFFD27C.toInt()), lp(MATCH, WRAP)) }
        val k = card(out); section(k, "Notable studies (results first, then phase 3 and 4)")
        for (t in s.top) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)); isClickable = true; setOnClickListener { openUrl("https://clinicaltrials.gov/study/" + t.nct) } }
            text(r, t.title, 14f, Ui.TXT, true)
            text(r, t.nct + " · " + t.phase.ifBlank { "phase n/a" } + " · " + t.status + " · enrolment " + t.enroll, 12f, Ui.ACC)
            if (t.results.isNotBlank()) text(r, "Posted result: " + t.results, 12f, Ui.OK)
            k.addView(r)
        }
        text(out, "Charts describe the sample analysed, not every registered study.", 12f, Ui.MUT)
    }
}
