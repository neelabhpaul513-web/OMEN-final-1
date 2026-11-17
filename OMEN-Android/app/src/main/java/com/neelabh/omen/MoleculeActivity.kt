package com.neelabh.omen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout

/** Molecular structure viewer: 2D picture, rotating 3D model, properties and rule-based ADMET. */
class MoleculeActivity : DashActivity() {
    private lateinit var out: LinearLayout
    private var mv: Pharma.MolView? = null
    private var show3d = false
    private var web: WebView? = null

    private fun html3d(sdf: String): String =
        "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<script src='https://cdnjs.cloudflare.com/ajax/libs/3Dmol/2.0.4/3Dmol-min.js'></script>" +
        "<style>html,body{margin:0;height:100%;background:#0e0e11}#v{width:100%;height:100%;position:relative}</style></head><body><div id='v'></div><script>" +
        "var v=\$3Dmol.createViewer('v',{backgroundColor:'0x0e0e11'});v.addModel(" + org.json.JSONObject.quote(sdf) + ",'sdf');" +
        "v.setStyle({},{stick:{radius:0.14},sphere:{scale:0.28}});v.zoomTo();v.render();v.spin('y',0.6);</script></body></html>"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); build("Molecular Structure Viewer")
        val c = card()
        val name = input(c, "Drug or compound name (e.g. aspirin, metformin, imatinib)", intent.getStringExtra("name") ?: "")
        button(c, "Show molecule") { val n = name.text.toString().trim(); if (n.isNotEmpty()) load(n, false, n) }
        out = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(out, lp(MATCH, WRAP))
        val smiles = intent.getStringExtra("smiles") ?: ""
        if (smiles.isNotBlank()) load(smiles, true, name.text.toString().ifBlank { "compound" })
        else if (name.text.isNotBlank()) load(name.text.toString().trim(), false, name.text.toString().trim())
    }

    private fun load(q: String, bySmiles: Boolean, display: String) {
        out.removeAllViews(); text(out, "Loading from PubChem…", 14f, Ui.MUT)
        bg { val v = Pharma.molecule(this, q, bySmiles, display); ui { mv = v; render() } }
    }

    private fun render() {
        out.removeAllViews(); val v = mv ?: return; val m = v.mol
        val c = card(out)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(chip("2D", !show3d) { show3d = false; render() }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
        chips.addView(chip(if (v.is3d) "3D (rotating)" else "3D (flat model)", show3d) { show3d = true; render() })
        c.addView(chips, lp(WRAP, WRAP).apply { bottomMargin = dp(8) })
        if (!show3d) {
            val bmp = v.png?.let { BitmapFactory.decodeFile(it) }
            if (bmp != null) c.addView(ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt()) }, lp(MATCH, WRAP))
            else text(c, "No picture available.", 13f, Ui.MUT)
        } else if (v.sdf != null) {
            val w = WebView(this); web = w
            w.settings.javaScriptEnabled = true; w.setBackgroundColor(Ui.BG)
            w.loadDataWithBaseURL("https://omen.local/", html3d(v.sdf), "text/html", "utf-8", null)
            c.addView(w, lp(MATCH, dp(340)))
            text(c, "Drag to rotate, pinch to zoom. Needs internet for the 3D library.", 12f, Ui.MUT)
        } else text(c, "No 3D data for this compound.", 13f, Ui.MUT)

        val p = card(out); section(p, "Properties")
        text(p, Pharma.molText(m), 13f, Ui.TXT)
        button(p, "Copy SMILES") { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SMILES", m.smiles)); toast("Copied") }
        button(p, "Open in PubChem") { openUrl("https://pubchem.ncbi.nlm.nih.gov/compound/" + m.cid) }

        val a = card(out); section(a, "ADMET (rule-based estimate, not measured data)")
        v.admet.forEach { text(a, "• $it", 13f, Ui.TXT) }
        text(a, "Drug-likeness score " + v.score + "/100", 15f, Ui.ACC, true)

        val x = card(out); section(x, "More")
        button(x, "Drug targets (ChEMBL)") { toast("Looking up…"); bg { val o = Pharma.asCommand(this, "targets of " + m.name); ui { showText("Drug targets", o?.text ?: "Nothing found.") } } }
        button(x, "Research this drug") { startActivity(Intent(this, PharmaDashActivity::class.java).putExtra("q", m.name)) }
    }

    override fun onDestroy() { super.onDestroy(); try { web?.destroy() } catch (e: Exception) {} }
}
