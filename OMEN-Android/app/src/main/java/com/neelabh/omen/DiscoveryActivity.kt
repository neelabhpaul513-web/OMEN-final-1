package com.neelabh.omen

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import java.util.Locale

/** AI Drug Discovery dashboard: target to ranked hits to docking preparation. */
class DiscoveryActivity : DashActivity() {
    private lateinit var out: LinearLayout
    private var disc: Pharma.Discovery? = null
    private var sort = "overall"

    private fun f(x: Double?) = if (x == null) "n/a" else String.format(Locale.US, "%.1f", x)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); build("AI Drug Discovery")
        val c = card()
        text(c, "Pick a protein target. OMEN finds the most potent measured compounds in ChEMBL, ranks them by potency and rule-based drug-likeness, and prepares docking.", 13f, Ui.MUT)
        val t = input(c, "Target or gene (e.g. EGFR, ACE, COX-2, HMG-CoA reductase)")
        button(c, "Find potent compounds") { run(t.text.toString().trim()) }
        out = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(out, lp(MATCH, WRAP))
    }

    private fun run(t: String) {
        if (t.isEmpty()) { toast("Type a target"); return }
        out.removeAllViews(); text(out, "Searching ChEMBL and RCSB… about 20 seconds", 14f, Ui.MUT)
        bg { val d = Pharma.discover(t); ui { disc = d; render() } }
    }

    private fun render() {
        out.removeAllViews(); val d = disc ?: return
        val c = card(out); section(c, "Target")
        text(c, d.targetName + "  (" + d.targetId + ")" + (if (d.uniprot.isNotBlank()) " · UniProt " + d.uniprot else ""), 16f, Ui.TXT, true)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, k) in listOf("Overall" to "overall", "Potency" to "potency", "Drug-likeness" to "dl")) chips.addView(chip(label, sort == k) { sort = k; render() }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
        out.addView(chips, lp(WRAP, WRAP).apply { bottomMargin = dp(10) })
        val hits = when (sort) { "potency" -> d.hits.sortedBy { it.ic50 }; "dl" -> d.hits.sortedByDescending { it.dl }; else -> d.hits.sortedByDescending { it.score } }
        if (hits.isEmpty()) text(card(out), "No compounds with IC50 up to 1 uM found for this target.", 14f, Ui.MUT)
        for (h in hits) {
            val r = card(out); r.isClickable = true
            text(r, h.name, 16f, Ui.TXT, true)
            text(r, "IC50 " + Pharma.potency(h.ic50) + "  ·  MW " + f(h.mw) + "  ·  XLogP " + f(h.logp) + "  ·  TPSA " + f(h.tpsa), 13f, Ui.MUT)
            text(r, "Ro5 violations " + h.ro5 + "  ·  drug-likeness " + h.dl + "  ·  overall score " + h.score, 13f, Ui.ACC)
            r.setOnClickListener { startActivity(Intent(this, MoleculeActivity::class.java).putExtra("smiles", h.smiles).putExtra("name", h.name)) }
        }
        val dk = card(out); section(dk, "Docking preparation")
        text(dk, "3D structures of the target (RCSB PDB, best resolution first). Tap to open.", 13f, Ui.MUT)
        for (p in d.pdb) text(dk, p.id + "  ·  " + p.method + "  ·  " + p.res + "  ·  " + p.title.take(70), 13f, Ui.ACC).setOnClickListener { openUrl("https://www.rcsb.org/structure/" + p.id) }
        if (d.pdb.isEmpty()) text(dk, "None found automatically.", 13f, Ui.MUT)
        val named = hits.firstOrNull { !it.name.startsWith("CHEMBL") }
        if (named != null) button(dk, "Docking guide: " + named.name + " with " + d.targetName) {
            toast("Preparing…")
            bg { val o = Pharma.dockingPrep(this, named.name, d.targetName); ui { showText("Docking preparation", o.text) } }
        }
        val n = card(out); section(n, "Next steps")
        text(n, "1. Take the top three hits.\n2. Check their mechanisms in Pharma Research.\n3. Dock them with AutoDock Vina or SwissDock using a PDB structure above.\n4. Re-dock the native ligand first to validate the set-up.\n5. Confirm with the literature.\n\nRanking uses measured potency plus rule-based drug-likeness. It does not predict efficacy or safety.", 13f, Ui.TXT)
    }
}
