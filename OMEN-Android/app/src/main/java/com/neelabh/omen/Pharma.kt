package com.neelabh.omen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pharmaceutical and medical research engine.
 * Literature and regulatory data: PubMed, ClinicalTrials.gov, openFDA, DailyMed.
 * Molecular data: PubChem (structure and properties), ChEMBL (targets, mechanisms, potency), RCSB PDB (3D structures for docking).
 * Every source reports whether it worked; nothing fails silently.
 */
object Pharma {
    private const val UA = "OMEN/1.0 (pharmaceutical research assistant)"
    data class Out(val text: String, val refs: List<Pair<String, String>>, val image: String? = null, val speak: String = "")

    class Src(val name: String) {
        @Volatile var ok = false
        @Volatile var note = ""
        fun line() = "- $name: " + (if (ok) "OK, $note" else "not available (" + note.ifBlank { "no data" } + ")")
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun enc20(s: String) = enc(s).replace("+", "%20")
    private fun f1(x: Double?) = if (x == null) "n/a" else String.format(Locale.US, "%.1f", x)
    private fun f2(x: Double?) = if (x == null) "n/a" else String.format(Locale.US, "%.2f", x)

    // ---------------- HTTP with retries and clear errors ----------------
    private class NotFound(msg: String) : Exception(msg)

    private fun get(url: String, tries: Int = 3): String {
        var last: Exception? = null
        for (i in 0 until tries) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "application/json, text/xml, */*")
                val code = c.responseCode
                if (code in 200..299) return c.inputStream.bufferedReader().readText()
                if (code == 404) throw NotFound("HTTP 404, nothing found")
                if (code == 429 || code >= 500) { last = Exception("HTTP $code, server busy or rate limited"); Thread.sleep(1200L * (i + 1)); continue }
                throw Exception("HTTP $code")
            } catch (e: NotFound) { throw e } catch (e: java.io.IOException) { last = Exception("network problem: " + (e.message ?: e.javaClass.simpleName)); Thread.sleep(800L * (i + 1)) }
        }
        throw last ?: Exception("network problem")
    }

    private fun <T> task(pool: ExecutorService, src: Src, f: () -> T): Future<T?> =
        pool.submit(Callable<T?> { try { f() } catch (e: Exception) { src.ok = false; src.note = e.message ?: e.javaClass.simpleName; null } })

    private fun <T> Future<T?>.res(): T? = try { get(100, TimeUnit.SECONDS) } catch (e: Exception) { null }

    // ---------------- PubMed (proper XML parsing, evidence filters, retry queries) ----------------
    data class Paper(val pmid: String, val title: String, val journal: String, val year: String, val abstract: String, val authors: String, val doi: String, val types: List<String>)

    private fun level(types: List<String>): String = when {
        types.any { it.contains("Meta-Analysis", true) } -> "meta-analysis"
        types.any { it.contains("Systematic Review", true) } -> "systematic review"
        types.any { it.contains("Randomized Controlled Trial", true) } -> "RCT"
        types.any { it.contains("Clinical Trial", true) } -> "clinical trial"
        types.any { it.equals("Review", true) } -> "review"
        types.any { it.contains("Case Reports", true) } -> "case report"
        else -> "study"
    }

    private fun mix(p: List<Paper>) = p.groupBy { level(it.types) }.entries.joinToString(", ") { "${it.value.size} ${it.key}" }

    private fun esearch(q: String, n: Int): List<String> {
        val a = JSONObject(get("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&retmode=json&sort=relevance&retmax=$n&term=" + enc(q)))
            .getJSONObject("esearchresult").getJSONArray("idlist")
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun parsePubmed(xml: String): List<Paper> {
        val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        db.setEntityResolver(EntityResolver { _, _ -> InputSource(StringReader("")) })   // never download the DTD
        val doc = db.parse(InputSource(StringReader(xml)))
        val arts = doc.getElementsByTagName("PubmedArticle")
        val out = mutableListOf<Paper>()
        for (i in 0 until arts.length) {
            val a = arts.item(i) as Element
            fun first(tag: String, from: Element = a): String = (from.getElementsByTagName(tag).item(0)?.textContent ?: "").replace(Regex("\\s+"), " ").trim()
            val absEls = a.getElementsByTagName("AbstractText")
            val abs = (0 until absEls.length).joinToString(" ") { k ->
                val e = absEls.item(k) as Element; val lab = e.getAttribute("Label")
                (if (lab.isNotEmpty()) "$lab: " else "") + e.textContent.replace(Regex("\\s+"), " ").trim()
            }
            val jr = a.getElementsByTagName("Journal").item(0) as? Element
            val journal = if (jr != null) first("Title", jr) else ""
            val pd = a.getElementsByTagName("PubDate").item(0) as? Element
            val year = if (pd != null) first("Year", pd).ifEmpty { Regex("\\d{4}").find(first("MedlineDate", pd))?.value ?: "" } else ""
            val au = a.getElementsByTagName("Author"); val names = mutableListOf<String>()
            for (k in 0 until minOf(au.length, 3)) { val e = au.item(k) as Element; val ln = first("LastName", e); if (ln.isNotEmpty()) names.add(ln + " " + first("Initials", e)) }
            val authors = names.joinToString(", ") + (if (au.length > 3) " et al." else "")
            var doi = ""; val ids = a.getElementsByTagName("ArticleId")
            for (k in 0 until ids.length) { val e = ids.item(k) as Element; if (e.getAttribute("IdType") == "doi") doi = e.textContent.trim() }
            val pt = a.getElementsByTagName("PublicationType"); val types = (0 until pt.length).map { pt.item(it).textContent.trim() }
            val pmid = first("PMID"); val title = first("ArticleTitle")
            if (pmid.isNotEmpty() && title.isNotEmpty()) out.add(Paper(pmid, title, journal, year, abs, authors, doi, types))
        }
        return out
    }

    private fun pubmedSearch(q: String, pq: String, topic: String, src: Src): List<Paper> {
        val evidence = "(randomized controlled trial[pt] OR meta-analysis[pt] OR systematic review[pt] OR clinical trial[pt])"
        val tried = mutableListOf<String>(); val ids = LinkedHashSet<String>(); val errs = mutableListOf<String>()
        fun tryQ(query: String, n: Int) {
            if (query.isBlank() || query in tried) return
            tried.add(query)
            try { ids.addAll(esearch(query, n)) } catch (e: Exception) { errs.add(e.message ?: "error") }
        }
        tryQ("($pq) AND $evidence", 4)                      // strongest evidence first
        tryQ(pq, 5)
        if (ids.size < 3) tryQ(q, 5)                        // retry with the raw question
        if (ids.size < 3) tryQ(topic, 5)                    // then just the topic
        if (ids.size < 3) tryQ(pq.replace(Regex("\\b(AND|OR|NOT)\\b"), " ").replace(Regex("[()\"\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim(), 5)
        if (ids.isEmpty()) { if (errs.isNotEmpty()) throw Exception(errs.first()); return emptyList() }
        return parsePubmed(get("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pubmed&retmode=xml&id=" + ids.take(8).joinToString(",")))
    }

    // ---------------- ClinicalTrials.gov (broader search, outcome extraction) ----------------
    data class Trial(val nct: String, val title: String, val status: String, val phase: String, val enroll: String, val sponsor: String,
                     val conditions: String, val interventions: String, val primary: String, val results: String, val dates: String, val hasResults: Boolean)

    private fun resultLine(rs: JSONObject?): String {
        val m = rs?.optJSONObject("outcomeMeasuresModule")?.optJSONArray("outcomeMeasures")?.optJSONObject(0) ?: return ""
        val groups = HashMap<String, String>()
        m.optJSONArray("groups")?.let { g -> for (i in 0 until g.length()) { val o = g.getJSONObject(i); groups[o.optString("id")] = o.optString("title") } }
        val vals = mutableListOf<String>()
        m.optJSONArray("classes")?.optJSONObject(0)?.optJSONArray("categories")?.optJSONObject(0)?.optJSONArray("measurements")?.let { ms ->
            for (i in 0 until minOf(ms.length(), 4)) { val o = ms.getJSONObject(i); vals.add((groups[o.optString("groupId")] ?: o.optString("groupId")) + " " + o.optString("value")) }
        }
        return m.optString("title") + (if (vals.isNotEmpty()) ": " + vals.joinToString(" vs ") + " " + m.optString("unitOfMeasure") else "")
    }

    private fun parseTrials(arr: JSONArray): List<Trial> {
        val out = mutableListOf<Trial>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i); val p = s.optJSONObject("protocolSection") ?: continue
            val idm = p.optJSONObject("identificationModule"); val st = p.optJSONObject("statusModule"); val de = p.optJSONObject("designModule")
            val sp = p.optJSONObject("sponsorCollaboratorsModule"); val co = p.optJSONObject("conditionsModule")
            val ai = p.optJSONObject("armsInterventionsModule"); val om = p.optJSONObject("outcomesModule")
            val nct = idm?.optString("nctId") ?: ""; if (!nct.startsWith("NCT")) continue
            val phase = de?.optJSONArray("phases")?.let { a -> (0 until a.length()).joinToString("/") { a.getString(it) } } ?: ""
            val primary = om?.optJSONArray("primaryOutcomes")?.let { a -> (0 until minOf(a.length(), 2)).joinToString("; ") { k -> a.getJSONObject(k).optString("measure") + " [" + a.getJSONObject(k).optString("timeFrame") + "]" } } ?: ""
            val inter = ai?.optJSONArray("interventions")?.let { a -> (0 until minOf(a.length(), 4)).joinToString(", ") { a.getJSONObject(it).optString("name") } } ?: ""
            val conds = co?.optJSONArray("conditions")?.let { a -> (0 until minOf(a.length(), 3)).joinToString(", ") { a.getString(it) } } ?: ""
            val rs = s.optJSONObject("resultsSection")
            out.add(Trial(nct, idm?.optString("briefTitle") ?: "", st?.optString("overallStatus") ?: "", phase,
                de?.optJSONObject("enrollmentInfo")?.optString("count") ?: "", sp?.optJSONObject("leadSponsor")?.optString("name") ?: "", conds, inter, primary,
                resultLine(rs), (st?.optJSONObject("startDateStruct")?.optString("date") ?: "") + " to " + (st?.optJSONObject("completionDateStruct")?.optString("date") ?: "?"),
                s.optBoolean("hasResults") || rs != null))
        }
        return out
    }

    private fun trialsFetch(drug: String, topic: String, src: Src): List<Trial> {
        val base = "https://clinicaltrials.gov/api/v2/studies?pageSize=12&query."
        val urls = mutableListOf<String>()
        if (drug.isNotBlank()) urls.add(base + "intr=" + enc(drug))
        urls.add(base + "term=" + enc(if (drug.isNotBlank()) drug else topic))
        if (topic.isNotBlank() && topic != drug) urls.add(base + "cond=" + enc(topic))
        var err = ""
        for (u in urls) {
            try {
                val arr = JSONObject(get(u)).optJSONArray("studies")
                if (arr != null && arr.length() > 0) {
                    val all = parseTrials(arr)
                    return all.sortedWith(compareByDescending<Trial> { it.hasResults }.thenByDescending { it.phase.contains("PHASE3") || it.phase.contains("PHASE4") }).take(5)
                }
            } catch (e: Exception) { err = e.message ?: "error" }
        }
        if (err.isNotEmpty()) throw Exception(err)
        return emptyList()
    }

    // ---------------- openFDA label (explicit OR, alternative names) and DailyMed ----------------
    class Label(val text: String, val setId: String, val names: String, val effective: String)
    class DM(val title: String, val setId: String, val date: String)

    private fun fdaFetch(names: List<String>, src: Src): Label? {
        var err = ""
        for (n in names.map { it.trim() }.filter { it.isNotBlank() }.distinct()) {
            val terms = listOf("openfda.generic_name:\"$n\" OR openfda.brand_name:\"$n\" OR openfda.substance_name:\"$n\"", "openfda.generic_name:$n* OR openfda.brand_name:$n*")
            for (term in terms) {
                try {
                    val r = JSONObject(get("https://api.fda.gov/drug/label.json?limit=1&search=" + enc(term))).optJSONArray("results")?.optJSONObject(0) ?: continue
                    fun sec(k: String, label: String): String { val a = r.optJSONArray(k) ?: return ""; return label + ": " + a.optString(0).replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(550) + "\n" }
                    val text = sec("indications_and_usage", "Indications") + sec("mechanism_of_action", "Mechanism (label)") + sec("clinical_pharmacology", "Clinical pharmacology") +
                        sec("dosage_and_administration", "Dosage and administration") + sec("contraindications", "Contraindications") + sec("warnings_and_cautions", "Warnings") +
                        sec("warnings", "Warnings") + sec("adverse_reactions", "Adverse reactions") + sec("drug_interactions", "Drug interactions")
                    if (text.isBlank()) continue
                    val of = r.optJSONObject("openfda")
                    val nm = (of?.optJSONArray("generic_name")?.optString(0) ?: n) + (of?.optJSONArray("brand_name")?.optString(0)?.let { " (" + it + ")" } ?: "")
                    return Label(text, r.optString("set_id"), nm, r.optString("effective_time").let { if (it.length == 8) it.substring(0, 4) + "-" + it.substring(4, 6) + "-" + it.substring(6) else it })
                } catch (e: NotFound) { } catch (e: Exception) { err = e.message ?: "error" }
            }
        }
        if (err.isNotEmpty()) throw Exception(err)
        return null
    }

    private fun dailymed(name: String): List<DM> {
        val arr = JSONObject(get("https://dailymed.nlm.nih.gov/dailymed/services/v2/spls.json?pagesize=3&drug_name=" + enc(name))).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); DM(o.optString("title"), o.optString("setid"), o.optString("published_date")) }
    }

    // ---------------- PubChem: structure and properties ----------------
    data class Mol(val cid: Long, val name: String, val formula: String, val mw: Double, val smiles: String, val iupac: String, val xlogp: Double?, val tpsa: Double?,
                   val hbd: Int, val hba: Int, val rot: Int, val charge: Int, val complexity: Double?, val heavy: Int)

    private fun pubchem(name: String): Mol {
        val props = "MolecularFormula,MolecularWeight,SMILES,ConnectivitySMILES,CanonicalSMILES,IsomericSMILES,IUPACName,XLogP,TPSA,HBondDonorCount,HBondAcceptorCount,RotatableBondCount,Charge,Complexity,HeavyAtomCount"
        val p = JSONObject(get("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/name/" + enc20(name) + "/property/" + props + "/JSON"))
            .getJSONObject("PropertyTable").getJSONArray("Properties").getJSONObject(0)
        fun d(k: String): Double? = if (p.has(k)) p.opt(k)?.toString()?.toDoubleOrNull() else null
        val smiles = listOf("SMILES", "IsomericSMILES", "CanonicalSMILES", "ConnectivitySMILES").map { p.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""
        return Mol(p.optLong("CID"), name, p.optString("MolecularFormula"), d("MolecularWeight") ?: 0.0, smiles, p.optString("IUPACName"), d("XLogP"), d("TPSA"),
            p.optInt("HBondDonorCount"), p.optInt("HBondAcceptorCount"), p.optInt("RotatableBondCount"), p.optInt("Charge"), d("Complexity"), p.optInt("HeavyAtomCount"))
    }

    private fun molBlock(m: Mol) = "Name: ${m.name}   (PubChem CID ${m.cid})\nFormula: ${m.formula}   Molecular weight: ${f2(m.mw)} g/mol\nSMILES: ${m.smiles}\nIUPAC: ${m.iupac}\n" +
        "XLogP: ${f1(m.xlogp)}   TPSA: ${f1(m.tpsa)} A2   H-bond donors: ${m.hbd}   acceptors: ${m.hba}   Rotatable bonds: ${m.rot}\nHeavy atoms: ${m.heavy}   Complexity: ${f1(m.complexity)}   Charge: ${m.charge}"

    /** Rule-based ADMET estimate (Lipinski, Veber, TPSA and logP rules). Clearly not experimental data. */
    private fun admet(m: Mol): Pair<Int, List<String>> {
        val l = mutableListOf<String>(); var score = 100
        val logp = m.xlogp; val tpsa = m.tpsa
        var ro5 = 0
        if (m.mw > 500) ro5++
        if (logp != null && logp > 5) ro5++
        if (m.hbd > 5) ro5++
        if (m.hba > 10) ro5++
        score -= ro5 * 15
        l.add("Lipinski rule of five: $ro5 violation(s) " + (if (ro5 <= 1) "(oral drug-like)" else "(poor oral drug-likeness)"))
        val veber = m.rot <= 10 && (tpsa == null || tpsa <= 140)
        if (!veber) score -= 15
        l.add("Veber rule (rotatable bonds up to 10, TPSA up to 140): " + (if (veber) "pass, good oral bioavailability likely" else "fail, oral absorption may be limited"))
        l.add("Absorption: " + (if (tpsa != null && tpsa <= 140 && m.mw <= 500) "high gastrointestinal absorption likely" else if (tpsa != null && tpsa > 140) "low absorption likely (TPSA above 140)" else "uncertain"))
        val bbb = if (tpsa != null && tpsa <= 90 && m.mw <= 450 && m.hbd <= 3) "likely to cross the blood-brain barrier" else if (tpsa != null && tpsa > 120) "unlikely to cross the blood-brain barrier" else "blood-brain barrier crossing uncertain"
        l.add("Distribution: $bbb" + (if (logp != null && logp > 3) "; lipophilic, so wide tissue distribution and high plasma protein binding are likely" else ""))
        l.add("Solubility estimate (XLogP ${f1(logp)}): " + when { logp == null -> "unknown"; logp < 1 -> "high"; logp < 3 -> "good"; logp < 5 -> "moderate"; else -> "low" })
        l.add("Metabolism: " + (if (logp != null && logp > 3 && m.mw > 300) "lipophilic and larger, so hepatic (CYP) metabolism is likely" else "cannot be predicted by simple rules; check the label and ChEMBL for CYP data"))
        l.add("Excretion: " + (if (m.mw < 300 && tpsa != null && tpsa > 75 && (logp ?: 0.0) < 1) "small and polar, so renal elimination is likely" else "route cannot be predicted by simple rules"))
        l.add("Toxicity: hERG, liver toxicity and mutagenicity cannot be estimated from these properties; see label warnings and experimental data")
        return score.coerceIn(0, 100) to l
    }

    // ---------------- ChEMBL: targets, mechanisms, potency ----------------
    class Mech(val action: String, val moa: String, val target: String, val targetId: String, val type: String, val organism: String, val uniprot: String)
    class Act(val target: String, val type: String, val value: Double, val organism: String)

    private fun chemblId(name: String): String {
        val a = JSONObject(get("https://www.ebi.ac.uk/chembl/api/data/molecule/search.json?limit=3&q=" + enc(name))).optJSONArray("molecules")
        if (a == null || a.length() == 0) throw Exception("compound not found in ChEMBL")
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("pref_name").equals(name, true)) return o.getString("molecule_chembl_id") }
        return a.getJSONObject(0).getString("molecule_chembl_id")
    }

    private fun targetInfo(id: String): Triple<String, String, String> {   // name, organism, uniprot
        val j = JSONObject(get("https://www.ebi.ac.uk/chembl/api/data/target/$id.json"))
        val acc = j.optJSONArray("target_components")?.optJSONObject(0)?.optString("accession") ?: ""
        return Triple(j.optString("pref_name"), j.optString("organism"), acc)
    }

    private fun mechanisms(chembl: String): List<Mech> {
        val a = JSONObject(get("https://www.ebi.ac.uk/chembl/api/data/mechanism.json?limit=10&molecule_chembl_id=$chembl")).optJSONArray("mechanisms") ?: return emptyList()
        val out = mutableListOf<Mech>(); val seen = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); val tid = o.optString("target_chembl_id")
            if (tid.isEmpty() || !seen.add(tid + o.optString("mechanism_of_action"))) continue
            val ti = try { targetInfo(tid) } catch (e: Exception) { Triple(tid, "", "") }
            out.add(Mech(o.optString("action_type"), o.optString("mechanism_of_action"), ti.first, tid, "", ti.second, ti.third))
            if (out.size >= 4) break
        }
        return out
    }

    private fun activities(chembl: String): List<Act> {
        val a = JSONObject(get("https://www.ebi.ac.uk/chembl/api/data/activity.json?limit=25&standard_units=nM&standard_type__in=IC50,Ki,Kd,EC50&molecule_chembl_id=$chembl")).optJSONArray("activities") ?: return emptyList()
        val out = mutableListOf<Act>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); val v = o.optString("standard_value").toDoubleOrNull() ?: continue
            val tn = o.optString("target_pref_name"); if (tn.isBlank() || tn == "null") continue
            out.add(Act(tn, o.optString("standard_type"), v, o.optString("target_organism")))
        }
        return out.sortedBy { it.value }.distinctBy { it.target + it.type }.take(6)
    }

    private fun findTarget(name: String): Triple<String, String, String>? {   // chemblId, name, uniprot
        val a = JSONObject(get("https://www.ebi.ac.uk/chembl/api/data/target/search.json?limit=6&q=" + enc(name))).optJSONArray("targets") ?: return null
        if (a.length() == 0) return null
        var pick = a.getJSONObject(0)
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("organism") == "Homo sapiens" && o.optString("target_type") == "SINGLE PROTEIN") { pick = o; break } }
        val acc = pick.optJSONArray("target_components")?.optJSONObject(0)?.optString("accession") ?: ""
        return Triple(pick.optString("target_chembl_id"), pick.optString("pref_name"), acc)
    }

    private fun nm(v: Double) = if (v >= 1000) f1(v / 1000) + " uM" else f1(v) + " nM"

    // ---------------- RCSB PDB: 3D structures for docking ----------------
    class Pdb(val id: String, val title: String, val method: String, val res: String)

    private fun pdbFor(uniprot: String): List<Pdb> {
        val body = "{\"query\":{\"type\":\"group\",\"logical_operator\":\"and\",\"nodes\":[{\"type\":\"terminal\",\"service\":\"text\",\"parameters\":{\"attribute\":\"rcsb_polymer_entity_container_identifiers.reference_sequence_identifiers.database_accession\",\"operator\":\"exact_match\",\"value\":\"$uniprot\"}},{\"type\":\"terminal\",\"service\":\"text\",\"parameters\":{\"attribute\":\"rcsb_polymer_entity_container_identifiers.reference_sequence_identifiers.database_name\",\"operator\":\"exact_match\",\"value\":\"UniProt\"}}]},\"return_type\":\"entry\",\"request_options\":{\"paginate\":{\"start\":0,\"rows\":4},\"sort\":[{\"sort_by\":\"rcsb_entry_info.resolution_combined\",\"direction\":\"asc\"}]}}"
        val arr = JSONObject(get("https://search.rcsb.org/rcsbsearch/v2/query?json=" + enc(body))).optJSONArray("result_set") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val id = arr.getJSONObject(i).optString("identifier")
            try {
                val e = JSONObject(get("https://data.rcsb.org/rest/v1/core/entry/$id"))
                Pdb(id, e.optJSONObject("struct")?.optString("title") ?: "", e.optJSONArray("exptl")?.optJSONObject(0)?.optString("method") ?: "",
                    e.optJSONObject("rcsb_entry_info")?.optJSONArray("resolution_combined")?.optDouble(0)?.let { f2(it) + " A" } ?: "n/a")
            } catch (e: Exception) { null }
        }
    }

    private fun download(c: Context, url: String): String? = try {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 30000; con.setRequestProperty("User-Agent", UA)
        val dir = File(c.filesDir, "img"); dir.mkdirs()
        val f = File(dir, "mol${System.currentTimeMillis()}.png")
        con.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
        if (f.length() > 500) f.path else null
    } catch (e: Exception) { null }

    // ---------------- molecular commands (no AI needed, so they are fast and reliable) ----------------
    private val P_DOCK = Regex("^(?:molecular )?dock(?:ing)?\\s+(.+?)\\s+(?:with|into|to|against|on|and)\\s+(.+)$")
    private val P_ADMET = Regex("^admet(?: prediction| profile| analysis| properties)?(?:\\s+(?:of|for))?\\s*(.+)$")
    private val P_TARGET = Regex("^(?:drug[- ]target(?: interactions?)?(?: research| analysis)?|targets? of|targets? for|what does (.+) target)\\s*(?:of|for)?\\s*(.*)$")
    private val P_SCREEN = Regex("^(?:compound screening|virtual screening|screen(?: compounds?)?|compare(?: drugs| compounds)?)\\s*(?:of|for|:)?\\s*(.+,.+)$")
    private val P_ACTIVE = Regex("^(?:compounds?|inhibitors?|drugs?|ligands?)\\s+(?:active\\s+)?against\\s+(.+)$")
    private val P_STRUCT = Regex("^(?:molecular structure analysis|analy[sz]e (?:the )?(?:molecule|structure|compound)|molecular structure|molecule|(?:chemical )?structure(?= of))\\s+(?:of|for)?\\s*(.+)$")

    fun isCommand(l: String): Boolean = listOf(P_DOCK, P_ADMET, P_TARGET, P_SCREEN, P_ACTIVE, P_STRUCT).any { it.containsMatchIn(l.trim()) }

    fun run(c: Context, key: String, q: String): Out = command(c, q) ?: research(key, q)

    private fun command(c: Context, q: String): Out? {
        val l = q.trim().lowercase().trimEnd('.', '?', '!')
        P_DOCK.find(l)?.let { return docking(c, it.groupValues[1].trim(), it.groupValues[2].trim()) }
        P_ADMET.find(l)?.let { return admetCard(c, it.groupValues[1].trim()) }
        P_SCREEN.find(l)?.let { return screen(it.groupValues[1].split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }) }
        P_ACTIVE.find(l)?.let { return targetScreen(it.groupValues[1].trim()) }
        P_TARGET.find(l)?.let { val n = (it.groupValues[1] + " " + it.groupValues[2]).trim(); if (n.isNotEmpty()) return targets(n) }
        P_STRUCT.find(l)?.let { return profile(c, it.groupValues[1].trim()) }
        return null
    }

    private fun fail(what: String, e: Exception) = Out("$what failed: ${e.message}\nNothing was guessed. Check the spelling of the name and try again.", emptyList())

    private fun profile(c: Context, name: String): Out = try {
        val m = pubchem(name); val (score, lines) = admet(m)
        val img = download(c, "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/${m.cid}/PNG?image_size=400x400")
        Out("MOLECULAR STRUCTURE ANALYSIS (PubChem, retrieved ${today()})\n" + molBlock(m) + "\n\nQUICK DRUG-LIKENESS (rule-based)\n- " + lines.take(2).joinToString("\n- ") + "\nDrug-likeness score: $score/100\n\nTip: say \"ADMET of ${m.name}\" for the full profile, or \"targets of ${m.name}\".",
            listOf("PubChem compound page, CID ${m.cid}" to "https://pubchem.ncbi.nlm.nih.gov/compound/${m.cid}", "3D conformer (SDF) for docking" to "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/${m.cid}/SDF?record_type=3d"), img,
            "${m.name}: formula ${m.formula}, molecular weight ${f1(m.mw)}.")
    } catch (e: Exception) { fail("Structure lookup", e) }

    private fun admetCard(c: Context, name: String): Out = try {
        val m = pubchem(name); val (score, lines) = admet(m)
        val img = download(c, "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/${m.cid}/PNG?image_size=400x400")
        Out("ADMET PREDICTION (rule-based estimate from PubChem properties, retrieved ${today()})\n" + molBlock(m) + "\n\n- " + lines.joinToString("\n- ") +
            "\n\nDrug-likeness score: $score/100\n\nNOTE: these are rule-of-thumb estimates (Lipinski, Veber, TPSA, logP). They are not experimental data and do not replace in-vitro, in-vivo or dedicated prediction tools such as SwissADME or pkCSM.",
            listOf("PubChem compound page, CID ${m.cid}" to "https://pubchem.ncbi.nlm.nih.gov/compound/${m.cid}", "SwissADME (run the SMILES above)" to "https://www.swissadme.ch", "pkCSM ADMET (run the SMILES above)" to "https://biosig.lab.uq.edu.au/pkcsm/prediction"), img,
            "${m.name}: drug-likeness score $score out of 100, rule-based.")
    } catch (e: Exception) { fail("ADMET prediction", e) }

    private fun targets(name: String): Out = try {
        val id = chemblId(name)
        val mech = try { mechanisms(id) } catch (e: Exception) { emptyList() }
        val acts = try { activities(id) } catch (e: Exception) { emptyList() }
        val refs = mutableListOf("ChEMBL compound report, $id" to "https://www.ebi.ac.uk/chembl/compound_report_card/$id/")
        val sb = StringBuilder("DRUG-TARGET INTERACTIONS (ChEMBL, retrieved ${today()})\nCompound: $name  ($id)\n\nMECHANISM OF ACTION\n")
        if (mech.isEmpty()) sb.append("- No curated mechanism record in ChEMBL for this compound.\n")
        mech.forEach { m ->
            sb.append("- ${m.action.lowercase().replaceFirstChar { it.uppercase() }} of ${m.target}" + (if (m.organism.isNotBlank()) " (${m.organism})" else "") + ": ${m.moa}\n")
            refs.add("Target ${m.target}, ${m.targetId}" to "https://www.ebi.ac.uk/chembl/target_report_card/${m.targetId}/")
            if (m.uniprot.isNotBlank()) refs.add("UniProt ${m.uniprot} (${m.target})" to "https://www.uniprot.org/uniprotkb/${m.uniprot}")
        }
        sb.append("\nMEASURED POTENCY (most potent records in ChEMBL)\n")
        if (acts.isEmpty()) sb.append("- No IC50/Ki/Kd/EC50 records in nM found.\n")
        acts.forEach { sb.append("- ${it.type} ${nm(it.value)} against ${it.target}" + (if (it.organism.isNotBlank()) " (${it.organism})" else "") + "\n") }
        sb.append("\nThese are literature-curated experimental values, not predictions. Lower is more potent.")
        Out(sb.toString(), refs, null, if (mech.isNotEmpty()) mech[0].moa.take(200) else "Target data for $name is ready.")
    } catch (e: Exception) { fail("Target lookup", e) }

    private fun docking(c: Context, drug: String, target: String): Out = try {
        val m = pubchem(drug)
        val t = findTarget(target) ?: throw Exception("target '$target' not found in ChEMBL")
        val pdb = if (t.third.isNotBlank()) try { pdbFor(t.third) } catch (e: Exception) { emptyList() } else emptyList()
        val chembl = try { chemblId(drug) } catch (e: Exception) { "" }
        val known = if (chembl.isNotEmpty()) try { activities(chembl).filter { it.target.contains(t.second.take(8), true) } } catch (e: Exception) { emptyList() } else emptyList()
        val refs = mutableListOf("Ligand ${m.name}, PubChem CID ${m.cid}" to "https://pubchem.ncbi.nlm.nih.gov/compound/${m.cid}", "Ligand 3D conformer (SDF)" to "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/${m.cid}/SDF?record_type=3d",
            "Target ${t.second}, ${t.first}" to "https://www.ebi.ac.uk/chembl/target_report_card/${t.first}/")
        if (t.third.isNotBlank()) refs.add("UniProt ${t.third}" to "https://www.uniprot.org/uniprotkb/${t.third}")
        pdb.forEach { refs.add("PDB ${it.id} (${it.method}, ${it.res})" to "https://www.rcsb.org/structure/${it.id}") }
        refs.add("SwissDock (web docking)" to "https://www.swissdock.ch"); refs.add("CB-Dock2 (blind docking, web)" to "https://cadd.labshare.cn/cb-dock2/"); refs.add("AutoDock Vina" to "https://vina.scripps.edu")
        val sb = StringBuilder("MOLECULAR DOCKING PREPARATION (retrieved ${today()})\nNo docking is run on the phone, because it needs 3D protein files and a docking engine. OMEN gathers everything you need and does not invent scores.\n\n")
        sb.append("LIGAND\n" + molBlock(m) + "\n\nTARGET\n- ${t.second} (${t.first})" + (if (t.third.isNotBlank()) ", UniProt ${t.third}" else "") + "\n\nBEST 3D STRUCTURES (RCSB PDB, sorted by resolution)\n")
        if (pdb.isEmpty()) sb.append("- None found automatically. Search the UniProt ID on rcsb.org, or use an AlphaFold model.\n")
        pdb.forEach { sb.append("- ${it.id}  ${it.method}  ${it.res}  ${it.title.take(90)}\n") }
        sb.append("\nEXPERIMENTAL EVIDENCE (use it to judge any docking result)\n")
        if (known.isEmpty()) sb.append("- No measured potency of $drug against this target found in ChEMBL.\n")
        known.forEach { sb.append("- ${it.type} ${nm(it.value)} against ${it.target}\n") }
        sb.append("\nHOW TO RUN IT\n1. Protein: download the best PDB, remove waters and other chains, add hydrogens and charges.\n2. Ligand: use the SDF link, set protonation at pH 7.4, minimise energy.\n3. Grid box: centre it on the co-crystallised ligand.\n4. Validate: re-dock the native ligand; RMSD under 2 A means the setup is reliable.\n5. Dock with AutoDock Vina or a web server below, then compare the pose and score with the potency above.")
        Out(sb.toString(), refs, null, "Docking preparation for $drug and ${t.second} is ready.")
    } catch (e: Exception) { fail("Docking preparation", e) }

    private fun screen(names: List<String>): Out {
        val rows = mutableListOf<Triple<Int, String, String>>(); val bad = mutableListOf<String>(); val refs = mutableListOf<Pair<String, String>>()
        for (n in names.take(8)) {
            try {
                val m = pubchem(n); val (score, lines) = admet(m)
                val ro5 = lines[0].substringAfter(": ").substringBefore(" ")
                rows.add(Triple(score, n, "MW ${f1(m.mw)}  XLogP ${f1(m.xlogp)}  TPSA ${f1(m.tpsa)}  Ro5 violations $ro5"))
                refs.add("$n, PubChem CID ${m.cid}" to "https://pubchem.ncbi.nlm.nih.gov/compound/${m.cid}")
            } catch (e: Exception) { bad.add("$n (${e.message})") }
        }
        if (rows.isEmpty()) return Out("Compound screening failed: no compound could be resolved.\n" + bad.joinToString("\n"), emptyList())
        val sorted = rows.sortedByDescending { it.first }
        val sb = StringBuilder("COMPOUND SCREENING (rule-based drug-likeness, PubChem data, retrieved ${today()})\nRanked best first:\n")
        sorted.forEachIndexed { i, r -> sb.append("${i + 1}. ${r.second}: score ${r.first}/100\n   ${r.third}\n") }
        if (bad.isNotEmpty()) sb.append("\nNot found: " + bad.joinToString("; ") + "\n")
        sb.append("\nThe score ranks oral drug-likeness only. It does not predict activity against a target. Use \"compounds active against <target>\" to see experimentally potent compounds.")
        return Out(sb.toString(), refs, null, "${sorted[0].second} ranks best of ${sorted.size} compounds.")
    }

    private fun targetScreen(target: String): Out = try {
        val t = findTarget(target) ?: throw Exception("target '$target' not found in ChEMBL")
        val a = JSONObject(get("https://www.ebi.ac.uk/chembl/api/data/activity.json?limit=40&standard_type=IC50&standard_units=nM&standard_value__lte=100&target_chembl_id=${t.first}")).optJSONArray("activities")
        val rows = mutableListOf<Triple<String, String, Double>>()
        if (a != null) for (i in 0 until a.length()) { val o = a.getJSONObject(i); val v = o.optString("standard_value").toDoubleOrNull() ?: continue
            rows.add(Triple(o.optString("molecule_chembl_id"), o.optString("molecule_pref_name").let { if (it.isBlank() || it == "null") "(unnamed)" else it }, v)) }
        val best = rows.sortedBy { it.third }.distinctBy { it.first }.take(10)
        val sb = StringBuilder("COMPOUNDS ACTIVE AGAINST ${t.second.uppercase()} (ChEMBL ${t.first}, IC50 up to 100 nM, retrieved ${today()})\n")
        if (best.isEmpty()) sb.append("- No records found.\n")
        best.forEachIndexed { i, r -> sb.append("${i + 1}. ${r.second} (${r.first}): IC50 ${nm(r.third)}\n") }
        sb.append("\nExperimental values from the literature, not predictions.")
        Out(sb.toString(), listOf("Target ${t.second}" to "https://www.ebi.ac.uk/chembl/target_report_card/${t.first}/") + best.take(5).map { "${it.second} ${it.first}" to "https://www.ebi.ac.uk/chembl/compound_report_card/${it.first}/" }, null, "${best.size} potent compounds found for ${t.second}.")
    } catch (e: Exception) { fail("Compound screening", e) }

    // ---------------- full research brief ----------------
    private fun plan(key: String, q: String): Triple<String, String, String> {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content",
            "A pharmacy student asks: \"$q\"\nReply with ONLY a JSON object: {\"drug\":\"main drug generic name, or empty\",\"pubmed\":\"a good PubMed search query with AND/OR, max 12 words\",\"topic\":\"3 to 6 word topic or condition\"}"))
        return try {
            val raw = Brain.txt(Brain.chat(key, msgs, null, 150))
            val j = JSONObject(Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(raw)?.value ?: "{}")
            val d = j.optString("drug").trim().let { if (it.equals("none", true) || it.equals("n/a", true) || it.equals("null", true)) "" else it }
            Triple(d, j.optString("pubmed").ifBlank { q }, j.optString("topic").ifBlank { q })
        } catch (e: Exception) { Triple("", q, q) }
    }

    private const val SYS = "You are OMEN, a pharmaceutical and medical research assistant for a B.Pharm student. Write a full structured research brief using ONLY the numbered SOURCES. Every sentence that states a fact must end with a citation like [1] or [2][3]. Never use outside knowledge and never invent numbers, doses or study results. If the sources do not cover a section, write 'not covered by the sources found'. Use plain text with these section headings in capitals, each followed by a short paragraph or dashed lines: SUMMARY, MOLECULAR STRUCTURE AND PROPERTIES, MECHANISM AND DRUG-TARGET INTERACTIONS, ADMET PROFILE (say it is a rule-based estimate unless a source reports measured data), CLINICAL EVIDENCE (study design, population, key outcomes), TRIAL OUTCOMES (from ClinicalTrials.gov records), INDICATIONS AND DOSING (as in the labelling), ADVERSE EFFECTS AND SAFETY, CONTRAINDICATIONS AND INTERACTIONS, REGULATORY AND LABEL STATUS, DOCKING AND SCREENING NOTES (what could be modelled; no docking was run), GAPS AND WHAT TO READ NEXT. No markdown symbols or asterisks."

    /** Marks any statement that has no citation and removes citation numbers that do not exist. */
    private fun validate(out: String, n: Int): Pair<String, String> {
        val cite = Regex("\\[(\\d{1,2})\\]")
        var total = 0; var cited = 0; var bad = 0
        val lines = out.lines().map { line ->
            val t = line.trim()
            if (t.length < 45 || t == t.uppercase() || t.startsWith("Educational")) return@map line
            t.split(Regex("(?<=[.!?])\\s+")).joinToString(" ") { s ->
                if (s.length < 40) s else {
                    total++
                    val ms = cite.findAll(s).toList()
                    if (ms.isEmpty()) "$s [unverified]" else {
                        cited++; var fixed = s
                        for (m in ms) { val k = m.groupValues[1].toInt(); if (k < 1 || k > n) { bad++; fixed = fixed.replace(m.value, "[?]") } }
                        fixed
                    }
                }
            }
        }
        val note = "CITATION CHECK: $cited of $total statements cite a source" + (if (total - cited > 0) "; ${total - cited} marked [unverified], treat them with caution" else "") + (if (bad > 0) "; $bad invalid citation number(s) replaced with [?]" else "") + "."
        return lines.joinToString("\n") to note
    }

    fun research(key: String, q: String): Out {
        val date = today()
        val sPub = Src("PubMed"); val sTri = Src("ClinicalTrials.gov"); val sFda = Src("openFDA label"); val sDm = Src("DailyMed"); val sMol = Src("PubChem"); val sChe = Src("ChEMBL")
        val (drug, pq, topic) = plan(key, q)
        val names = listOf(drug, drug.substringBefore(" "), drug.replace(Regex("\\b(hydrochloride|hcl|sodium|potassium|sulfate|sulphate|maleate|mesylate|tartrate|succinate|citrate|phosphate|acetate|besylate|calcium|disodium)\\b", RegexOption.IGNORE_CASE), "").trim())
        val pool = Executors.newFixedThreadPool(6)
        class R(val papers: List<Paper>, val trials: List<Trial>, val label: Label?, val dm: List<DM>, val mol: Mol?, val mech: List<Mech>, val acts: List<Act>)
        val r = try {
            val fPub = task(pool, sPub) { pubmedSearch(q, pq, topic, sPub) }
            val fTri = task(pool, sTri) { trialsFetch(drug, topic, sTri) }
            val fFda = task(pool, sFda) { if (drug.isBlank()) throw Exception("no drug name identified in the question") else fdaFetch(names, sFda) }
            val fDm = task(pool, sDm) { if (drug.isBlank()) throw Exception("no drug name identified in the question") else dailymed(drug) }
            val fMol = task(pool, sMol) { if (drug.isBlank()) throw Exception("no drug name identified in the question") else pubchem(drug) }
            val fChe = task(pool, sChe) { if (drug.isBlank()) throw Exception("no drug name identified in the question") else { val id = chemblId(drug); Pair(mechanisms(id), activities(id)) } }
            val che = fChe.res()
            R(fPub.res() ?: emptyList(), fTri.res() ?: emptyList(), fFda.res(), fDm.res() ?: emptyList(), fMol.res(), che?.first ?: emptyList(), che?.second ?: emptyList())
        } finally { pool.shutdownNow() }
        val papers = r.papers; val trials = r.trials; val label = r.label; val dm = r.dm; val mol = r.mol; val mech = r.mech; val acts = r.acts
        if (papers.isNotEmpty()) { sPub.ok = true; sPub.note = "${papers.size} papers (" + mix(papers) + ")" } else if (sPub.note.isBlank()) sPub.note = "no matching papers"
        if (trials.isNotEmpty()) { sTri.ok = true; sTri.note = "${trials.size} studies (${trials.count { it.hasResults }} with posted results)" } else if (sTri.note.isBlank()) sTri.note = "no matching studies"
        if (label != null) { sFda.ok = true; sFda.note = "label for ${label.names}, effective ${label.effective}" } else if (sFda.note.isBlank()) sFda.note = "no label matched ${names.firstOrNull { it.isNotBlank() } ?: "the name"}"
        if (dm.isNotEmpty()) { sDm.ok = true; sDm.note = "${dm.size} official label(s) listed" } else if (sDm.note.isBlank()) sDm.note = "no labels listed"
        if (mol != null) { sMol.ok = true; sMol.note = "CID ${mol.cid}, ${mol.formula}, MW ${f1(mol.mw)}" } else if (sMol.note.isBlank()) sMol.note = "compound not found"
        if (mech.isNotEmpty() || acts.isNotEmpty()) { sChe.ok = true; sChe.note = "${mech.size} mechanism(s), ${acts.size} potency record(s)" } else if (sChe.note.isBlank()) sChe.note = "no records"
        val status = "SOURCE STATUS (retrieved $date)\n" + listOf(sPub, sTri, sFda, sDm, sMol, sChe).joinToString("\n") { it.line() }
        val crossLabel = if (label != null && dm.isNotEmpty()) "\nLabel cross-check: the drug was found in both openFDA and DailyMed." else ""

        fun build(absLen: Int, labLen: Int, np: Int): Pair<String, List<Pair<String, String>>> {
            val ctx = StringBuilder(); val refs = mutableListOf<Pair<String, String>>(); var n = 0
            for (p in papers.take(np)) {
                n++; ctx.append("[$n] PubMed PMID ${p.pmid} (${level(p.types)}), ${p.authors}, ${p.journal} ${p.year}. ${p.title}. ${p.abstract.take(absLen)}\n\n")
                refs.add("PMID ${p.pmid} · ${level(p.types)} · ${p.authors}. ${p.title} ${p.journal} ${p.year}.${if (p.doi.isNotEmpty()) " doi:" + p.doi else ""} · retrieved $date" to "https://pubmed.ncbi.nlm.nih.gov/${p.pmid}/")
            }
            for (t in trials) {
                n++; ctx.append("[$n] ClinicalTrials.gov ${t.nct}: ${t.title}. Status ${t.status}; phase ${t.phase}; enrolment ${t.enroll}; sponsor ${t.sponsor}; conditions ${t.conditions}; interventions ${t.interventions}; dates ${t.dates}. Primary outcome: ${t.primary}." + (if (t.results.isNotEmpty()) " Posted result: ${t.results}." else "") + "\n")
                refs.add("${t.nct} · ${t.phase.ifBlank { "phase n/a" }} · ${t.status} · ${t.title.take(80)} · retrieved $date" to "https://clinicaltrials.gov/study/${t.nct}")
            }
            if (label != null) {
                n++; ctx.append("[$n] FDA drug label (openFDA), ${label.names}, effective ${label.effective}:\n${label.text.take(labLen)}\n")
                refs.add("FDA label (openFDA): ${label.names} · effective ${label.effective} · retrieved $date" to (if (label.setId.isNotEmpty()) "https://dailymed.nlm.nih.gov/dailymed/lookup.cfm?setid=${label.setId}" else "https://dailymed.nlm.nih.gov/dailymed/search.cfm?query=" + enc(drug)))
            }
            if (dm.isNotEmpty()) {
                n++; ctx.append("[$n] DailyMed official labels: " + dm.joinToString("; ") { "${it.title} (published ${it.date})" } + "\n")
                dm.take(2).forEach { refs.add("DailyMed: ${it.title.take(70)} · published ${it.date} · retrieved $date" to "https://dailymed.nlm.nih.gov/dailymed/lookup.cfm?setid=${it.setId}") }
            }
            val mm = mol
            if (mm != null) {
                val (score, lines) = admet(mm)
                n++; ctx.append("[$n] PubChem CID ${mm.cid}. ${molBlock(mm)}\nRule-based ADMET estimate (not measured data, score $score/100): ${lines.take(6).joinToString(" | ")}\n")
                refs.add("PubChem CID ${mm.cid} · retrieved $date" to "https://pubchem.ncbi.nlm.nih.gov/compound/${mm.cid}")
            }
            if (mech.isNotEmpty() || acts.isNotEmpty()) {
                n++; ctx.append("[$n] ChEMBL. Mechanisms: " + mech.joinToString("; ") { "${it.action} of ${it.target}: ${it.moa}" } + ". Measured potency: " + acts.joinToString("; ") { "${it.type} ${nm(it.value)} vs ${it.target}" } + "\n")
                mech.take(2).forEach { refs.add("ChEMBL target ${it.target} ${it.targetId} · retrieved $date" to "https://www.ebi.ac.uk/chembl/target_report_card/${it.targetId}/") }
            }
            return ctx.toString() to refs
        }

        var (ctx, refs) = build(700, 2400, 6)
        val nSources = ctx.lines().count { Regex("^\\[\\d+]").containsMatchIn(it) }
        if (nSources == 0) {
            val r = Brain.researchRefs(key, q)
            return Out("$status\n\nNO RESEARCH DATABASE RETURNED DATA, so the answer below is a general web answer and is NOT source-validated.\n\n" + r.text, r.refs, null, r.text.take(300))
        }
        fun write(c: String): String {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYS))
                .put(JSONObject().put("role", "user").put("content", "Question: $q\n\nSOURCES:\n$c\nWrite the research brief now."))
            return Brain.clean(Brain.txt(Brain.chat(key, msgs, null, 1800)))
        }
        val raw = try { write(ctx) } catch (e: Exception) {
            if (!Regex("rate limit|too large|try again|tokens|413|429", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")) throw e
            Thread.sleep(7000)
            val small = build(400, 1200, 4); ctx = small.first; refs = small.second
            write(ctx)
        }
        val nNow = ctx.lines().count { Regex("^\\[\\d+]").containsMatchIn(it) }
        val (checked, note) = validate(raw, nNow)
        val all = refs + listOf("PubMed search used: $pq · retrieved $date" to "https://pubmed.ncbi.nlm.nih.gov/?term=" + enc(pq), "Google Scholar" to "https://scholar.google.com/scholar?q=" + enc(q))
        val summary = Regex("SUMMARY\\s*(.+?)(?:\\n\\s*\\n|\\n[A-Z ,-]{6,}\\n)", RegexOption.DOT_MATCHES_ALL).find(checked)?.groupValues?.get(1)?.replace(Regex("\\[[0-9?]+]"), "")?.replace("[unverified]", "")?.trim() ?: checked.take(400)
        return Out("$status$crossLabel\n\n$checked\n\n$note", all, null, summary.take(450))
    }
}
