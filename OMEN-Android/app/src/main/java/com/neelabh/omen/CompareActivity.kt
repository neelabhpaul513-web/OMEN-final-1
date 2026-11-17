package com.neelabh.omen

import android.os.Bundle
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Drug comparison dashboard: 2 to 4 drugs side by side. */
class CompareActivity : DashActivity() {
    private lateinit var out: LinearLayout
    private var cards = listOf<Pharma.DrugCard>()
    private var rows = listOf<Pair<String, (Pharma.DrugCard) -> String>>()

    private fun f(x: Double?) = if (x == null) "n/a" else String.format(Locale.US, "%.1f", x)
    private fun r(label: String, g: (Pharma.DrugCard) -> String) = Pair(label, g)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); build("Drug Comparison")
        val c = card()
        text(c, "Enter 2 to 4 drug names separated by commas.", 13f, Ui.MUT)
        val q = input(c, "aspirin, ibuprofen, naproxen")
        button(c, "Compare") { run(q.text.toString()) }
        out = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(out, lp(MATCH, WRAP))
        rows = listOf(
            r("Formula") { it.mol?.formula ?: "n/a" },
            r("Molecular weight") { f(it.mol?.mw) },
            r("XLogP") { f(it.mol?.xlogp) },
            r("TPSA") { f(it.mol?.tpsa) },
            r("H-bond donors / acceptors") { if (it.mol == null) "n/a" else it.mol.hbd.toString() + " / " + it.mol.hba },
            r("Lipinski violations") { it.ro5 },
            r("Drug-likeness score") { if (it.mol == null) "n/a" else it.score.toString() + "/100" },
            r("Mechanism") { it.mech },
            r("Targets") { it.target },
            r("Indication (FDA label)") { it.indication },
            r("FDA label date") { it.labelDate },
            r("Clinical trials (count)") { it.trials },
            r("PubMed papers (count)") { it.papers })
    }

    private fun run(list: String) {
        val names = list.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (names.size < 2) { toast("Enter 2 to 4 names separated by commas"); return }
        out.removeAllViews(); text(out, "Comparing… about 20 seconds", 14f, Ui.MUT)
        bg { val cs = Pharma.compare(names); ui { cards = cs; render() } }
    }

    private fun cell(t: String, w: Int, bold: Boolean = false, color: Int = Ui.TXT) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(color); setPadding(dp(8), dp(8), dp(8), dp(8)); width = dp(w); gravity = Gravity.START
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun render() {
        out.removeAllViews()
        val tbl = TableLayout(this)
        val head = TableRow(this)
        head.addView(cell("", 120))
        cards.forEach { head.addView(cell(it.name, 150, true, Ui.ACC)) }
        tbl.addView(head)
        for ((label, g) in rows) {
            val tr = TableRow(this)
            tr.addView(cell(label, 120, false, Ui.MUT))
            cards.forEach { tr.addView(cell(g(it), 150)) }
            tbl.addView(tr)
        }
        out.addView(HorizontalScrollView(this).apply { addView(tbl) })
        cards.filter { it.error.isNotEmpty() }.forEach { text(out, it.name + ": " + it.error, 12f, Ui.BAD) }
        val best = cards.filter { it.mol != null }.maxByOrNull { it.score }
        if (best != null) text(out, "Best rule-based drug-likeness: " + best.name + " (" + best.score + "/100)", 14f, Ui.OK, true)
        val c = card(out)
        button(c, "AI comparison summary") {
            if (key().isBlank()) { toast("Add your Groq key in Settings"); return@button }
            toast("Writing…")
            bg {
                val sb = StringBuilder()
                cards.forEach { d -> sb.append(d.name).append(": ").append(rows.joinToString("; ") { it.first + " = " + it.second(d) }).append("\n") }
                val msgs = JSONArray().put(JSONObject().put("role", "user").put("content",
                    "Using ONLY this table of retrieved data, compare these drugs for a pharmacy student: key differences, which has the best drug-likeness, how their mechanisms differ, and how much evidence exists. Plain text, under 200 words. Do not add facts that are not in the table. Say 'n/a' items are unknown.\n\n" + sb))
                val ans = Brain.clean(Brain.txt(Brain.chat(key(), msgs, null, 700)))
                ui { showText("Comparison summary", ans + "\n\n(Based only on the table above.)") }
            }
        }
        text(c, "Drug-likeness is a rule-based oral estimate. Counts come from ClinicalTrials.gov and PubMed.", 12f, Ui.MUT)
    }
}
