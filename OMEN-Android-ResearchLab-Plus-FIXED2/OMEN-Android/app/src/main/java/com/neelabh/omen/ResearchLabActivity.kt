package com.neelabh.omen

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Research workbench: dedicated, navigable screens for OMEN's scientific tools. */
class ResearchLabActivity : Activity() {
    private val bg = 0xFF09090E.toInt(); private val card = 0xFF17171F.toInt()
    private val fg = 0xFFECECF2.toInt(); private val muted = 0xFF9A9AA8.toInt(); private val accent = 0xFFA8B8FF.toInt()
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun lp(w:Int,h:Int)= LinearLayout.LayoutParams(w,h)
    private val match get()= ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap get()= ViewGroup.LayoutParams.WRAP_CONTENT
    private lateinit var body: LinearLayout
    private lateinit var scroller: ScrollView
    private fun label(s:String, size:Float=15f, color:Int=fg)=TextView(this).apply { text=s; textSize=size; setTextColor(color); setPadding(dp(2),dp(5),dp(2),dp(5)) }
    private fun field(hintText:String)=EditText(this).apply { hint=hintText; setHintTextColor(muted); setTextColor(fg); textSize=16f; singleLine=true; setPadding(dp(12),dp(12),dp(12),dp(12)); background=round(card) }
    private fun round(c:Int)=android.graphics.drawable.GradientDrawable().apply { setColor(c); cornerRadius=dp(16).toFloat(); setStroke(dp(1),0x333F465E) }
    private fun button(s:String, action:()->Unit)=Button(this).apply { text=s; isAllCaps=false; setTextColor(fg); background=round(0xFF303448.toInt()); setOnClickListener { action() } }
    private fun section(title:String, subtitle:String, action:()->Unit) {
        val c=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=round(card); setPadding(dp(16),dp(13),dp(16),dp(13)); isClickable=true; isFocusable=true; setOnClickListener { action() } }
        c.addView(label(title,18f)); c.addView(label(subtitle,13f,muted)); body.addView(c,lp(match,wrap).apply { bottomMargin=dp(10) })
    }
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); window.statusBarColor=bg; window.navigationBarColor=bg; showHome() }
    private fun base(title:String, subtitle:String) {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(16),dp(24),dp(16),dp(12)) }
        val bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        bar.addView(TextView(this).apply { text="‹"; textSize=34f; setTextColor(fg); setOnClickListener { showHome() } },lp(dp(44),dp(48)))
        val titles=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        titles.addView(label(title,22f)); titles.addView(label(subtitle,12f,muted)); bar.addView(titles,lp(0,wrap).apply { weight=1f }); root.addView(bar)
        scroller=ScrollView(this).apply { isFillViewport=true }; body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(16),0,dp(36)) }; scroller.addView(body); root.addView(scroller,lp(match,0).apply { weight=1f }); setContentView(root)
    }
    private fun showHome() {
        base("OMEN RESEARCH LAB","Scientific tools · free public research sources")
        body.addView(label("RESEARCH WORKSPACE",12f,accent)); body.addView(label("Explore medicines, molecules and clinical evidence from one place.",15f,muted))
        section("Pharma Research","Evidence briefs from PubMed, trials and FDA labels") { pharma() }
        section("AI Drug Discovery","Research workflow: disease → target → compounds → evidence") { discovery() }
        section("Molecular Structure Viewer","Look up a compound and inspect its 2D structure") { molecule() }
        section("Drug Comparison","Compare two compounds using public chemical properties") { compare() }
        section("Clinical Trial Analytics","Search ClinicalTrials.gov and inspect study status") { trials() }
        section("Voice Control Centre","Wake word, listening and spoken-reply controls") { voice() }
        section("Image Generation Gallery","Browse images saved by OMEN in Pictures/OMEN") { gallery() }
        body.addView(label("Data sources: PubChem · ClinicalTrials.gov · existing OMEN Pharma Research pipeline",12f,muted))
    }
    private fun pharma() {
        base("PHARMA RESEARCH","Literature and regulatory evidence")
        val q=field("Drug, disease or research question"); body.addView(label("RESEARCH QUERY")); body.addView(q)
        val out=label("Your evidence brief will appear here.",14f,muted); body.addView(out)
        body.addView(button("Run pharma research") {
            val key=getSharedPreferences("omen",MODE_PRIVATE).getString("key","").orEmpty()
            if(key.isBlank()){ out.text="Add your Groq API key in OMEN Settings first."; return@button }
            out.text="Searching PubMed, ClinicalTrials.gov and FDA labels…"
            Thread { try { val result=Brain.pharmaResearch(key,q.text.toString()); runOnUiThread { out.text=result.text+"\n\nREFERENCES\n"+result.refs.mapIndexed { i,p -> "${i+1}. ${p.first}\n${p.second}" }.joinToString("\n\n") } } catch(e:Exception){runOnUiThread{out.text="Research failed: ${e.message}"}} }.start()
        })
    }
    private fun discovery() {
        base("AI DRUG DISCOVERY","A guided research workflow — not an automated validated discovery engine")
        val disease=field("Disease or biological condition"); val target=field("Target / gene / protein (optional)"); val compound=field("Candidate compound name (optional)")
        body.addView(label("1 · DISEASE / INDICATION")); body.addView(disease); body.addView(label("2 · TARGET HYPOTHESIS")); body.addView(target); body.addView(label("3 · CANDIDATE COMPOUND")); body.addView(compound)
        val result=label("Enter a disease and run the workflow to create a research worksheet.",14f,muted); body.addView(result)
        body.addView(button("Build research worksheet") {
            if(disease.text.isBlank()){result.text="Enter a disease or indication first.";return@button}
            result.text="RESEARCH WORKSHEET\n\nIndication: ${disease.text}\nProposed target: ${target.text.ifBlank { "Not specified — requires literature review" }}\nCandidate: ${compound.text.ifBlank { "Not specified" }}\n\nNext steps\n• Review disease biology and target validation in primary literature\n• Check target tractability and known ligands\n• Search PubChem for compound identity and properties\n• Review clinical and safety evidence\n• Validate all hypotheses experimentally\n\nThis is a planning worksheet, not a prediction of efficacy or a validated drug-discovery result."
        })
    }
    private fun molecule() {
        base("MOLECULAR VIEWER","PubChem compound lookup and 2D structure")
        val name=field("Compound name (e.g. caffeine or metformin)"); body.addView(label("COMPOUND")); body.addView(name)
        val result=label("Search a compound to load its PubChem 2D structure.",14f,muted); body.addView(result)
        body.addView(button("Find molecule") {
            val q=name.text.toString().trim(); if(q.isBlank()){result.text="Enter a compound name.";return@button}
            result.text="Looking up PubChem…"
            Thread { try {
                val encoded=URLEncoder.encode(q,"UTF-8"); val json=JSONObject(get("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/name/$encoded/property/MolecularFormula,MolecularWeight,ConnectivitySMILES/JSON"))
                val p=json.getJSONArray("PropertyTable").getJSONArray("Properties").getJSONObject(0); val cid=p.getInt("CID")
                runOnUiThread { result.text="CID: $cid\nFormula: ${p.optString("MolecularFormula")}\nMolecular weight: ${p.optString("MolecularWeight")} g/mol\nCanonical SMILES: ${p.optString("ConnectivitySMILES",p.optString("CanonicalSMILES"))}"; showStructure(cid,q) }
            } catch(e:Exception){runOnUiThread{result.text="No PubChem result or network error: ${e.message}"}} }.start()
        })
    }
    private fun showStructure(cid:Int,name:String) {
        val web=WebView(this); web.settings.javaScriptEnabled=false; web.loadUrl("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/$cid/PNG")
        AlertDialog.Builder(this).setTitle("2D structure · $name").setView(web).setPositiveButton("Close",null).show()
    }
    private fun compare() {
        base("DRUG COMPARISON","Side-by-side public chemical properties")
        val a=field("First compound"); val b=field("Second compound"); body.addView(label("COMPOUND A")); body.addView(a); body.addView(label("COMPOUND B")); body.addView(b)
        val out=label("Comparison results will appear here.",14f,muted); body.addView(out)
        body.addView(button("Compare compounds") {
            if(a.text.isBlank()||b.text.isBlank()){out.text="Enter both compound names.";return@button}; out.text="Querying PubChem…"
            Thread { try { val x=pubchem(a.text.toString()); val y=pubchem(b.text.toString()); runOnUiThread { out.text="PROPERTY\t${a.text}\t${b.text}\nCID\t${x.optString("CID")}\t${y.optString("CID")}\nFormula\t${x.optString("MolecularFormula")}\t${y.optString("MolecularFormula")}\nMolecular weight\t${x.optString("MolecularWeight")}\t${y.optString("MolecularWeight")}\nXLogP\t${x.optString("XLogP")}\t${y.optString("XLogP")}\nTPSA\t${x.optString("TPSA")}\t${y.optString("TPSA")}\n\nThese descriptors do not establish safety, potency or clinical equivalence." } } catch(e:Exception){runOnUiThread{out.text="Comparison failed: ${e.message}"}} }.start()
        })
    }
    private fun pubchem(n:String):JSONObject { val u=URLEncoder.encode(n.trim(),"UTF-8"); val j=JSONObject(get("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/name/$u/property/MolecularFormula,MolecularWeight,XLogP,TPSA/JSON")); return j.getJSONObject("PropertyTable").getJSONArray("Properties").getJSONObject(0) }
    private fun trials() {
        base("CLINICAL TRIAL ANALYTICS","ClinicalTrials.gov public API v2")
        val query=field("Condition, drug or intervention"); body.addView(label("SEARCH STUDIES")); body.addView(query)
        val out=label("Search results will appear here.",14f,muted); body.addView(out)
        body.addView(button("Search clinical trials") {
            if(query.text.isBlank()){out.text="Enter a search term.";return@button}; out.text="Searching ClinicalTrials.gov…"
            Thread { try { val u=URLEncoder.encode(query.text.toString(),"UTF-8"); val j=JSONObject(get("https://clinicaltrials.gov/api/v2/studies?pageSize=15&format=json&query.term=$u")); val arr=j.optJSONArray("studies"); val s=StringBuilder("Studies found: ${arr?.length() ?: 0}\n\n")
                if(arr!=null) for(i in 0 until arr.length()){val p=arr.getJSONObject(i).optJSONObject("protocolSection")?:continue;val id=p.optJSONObject("identificationModule");val st=p.optJSONObject("statusModule");val de=p.optJSONObject("designModule"); val nct=id?.optString("nctId").orEmpty();s.append("${i+1}. ${id?.optString("briefTitle")}\n$nct · ${st?.optString("overallStatus")} · ${de?.optJSONArray("phases")?.let{ar->(0 until ar.length()).joinToString(", "){ar.optString(it)}}}\nhttps://clinicaltrials.gov/study/$nct\n\n") }
                runOnUiThread{out.text=s.toString()}
            } catch(e:Exception){runOnUiThread{out.text="Trial search failed: ${e.message}"}} }.start()
        })
    }
    private fun voice() {
        base("VOICE CONTROL CENTRE","Manage OMEN's wake word and spoken responses")
        val p=getSharedPreferences("omen",MODE_PRIVATE)
        fun toggle(title:String,desc:String,key:String,def:Boolean){val sw=Switch(this).apply{text=title;setTextColor(fg);textSize=16f;isChecked=p.getBoolean(key,def);setOnCheckedChangeListener{_,v->p.edit().putBoolean(key,v).apply()}};body.addView(sw);body.addView(label(desc,12f,muted))}
        toggle("Offline Hey Omen detector","Uses the on-device Vosk model when downloaded.","hwake",true)
        toggle("Require wake word","Require Hey Omen before processing voice input.","wake",true)
        toggle("Smart wake","Allow clear commands without the wake phrase.","smart",true)
        toggle("Speak replies","Read OMEN's responses aloud.","voice",true)
        body.addView(label(if(WakeModel.ready(this))"Wake-word model: downloaded" else "Wake-word model: not downloaded. Download it in Settings.",13f,accent))
        body.addView(button("Open full voice settings"){startActivity(Intent(this,SettingsActivity::class.java))})
    }
    private fun gallery() {
        base("IMAGE GALLERY","Images saved in Pictures/OMEN")
        val grid=GridLayout(this).apply{columnCount=2}; body.addView(grid)
        try { val uri=MediaStore.Images.Media.EXTERNAL_CONTENT_URI; val projection=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME); val cursor=contentResolver.query(uri,projection,"${MediaStore.Images.Media.RELATIVE_PATH}=?",arrayOf("Pictures/OMEN/"),"${MediaStore.Images.Media.DATE_ADDED} DESC")
            var count=0; cursor?.use { while(it.moveToNext()){val id=it.getLong(0);val name=it.getString(1);val image=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setImageURI(ContentUris.withAppendedId(uri,id));layoutParams=ViewGroup.LayoutParams(dp(145),dp(145));setOnClickListener{startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(uri,id),"image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}};grid.addView(image);count++} }
            if(count==0)body.addView(label("No OMEN images saved yet. Create an image in chat and save it to Pictures/OMEN.",14f,muted))
        } catch(e:Exception){body.addView(label("Gallery unavailable: ${e.message}",14f,muted))}
    }
    private fun get(url:String):String { val c=URL(url).openConnection() as HttpURLConnection; c.connectTimeout=15000;c.readTimeout=20000;c.setRequestProperty("User-Agent","OMEN-Android/1.0 (research app)"); try { if(c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}"); return c.inputStream.bufferedReader().use{it.readText()} } finally { c.disconnect() } }
}
