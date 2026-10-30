'use strict';
const $ = (s) => document.querySelector(s);
let S = {}, chat = [], usage = {};
let models = [], brainM = '', webM = '';
const bad = new Set(), visBad = new Set();
let webMode = false, recording = false, rec = null;
const attach = { images: [], docs: [] };
const ctx = [];   // short memory for the language model

const PREF = ['llama-3.3-70b-versatile', 'openai/gpt-oss-120b', 'meta-llama/llama-4-maverick-17b-128e-instruct', 'meta-llama/llama-4-scout-17b-16e-instruct', 'openai/gpt-oss-20b', 'qwen/qwen3-32b', 'llama-3.1-8b-instant'];
const NONCHAT = /whisper|tts|guard|orpheus|embed|playai|compound|safeguard/i;
const VISHINT = /vision|vl\b|-vl|llama-4|scout|maverick|pixtral|llava|gemma-3|multimodal|omni/i;
const SITES = { youtube: 'https://youtube.com', google: 'https://google.com', gmail: 'https://mail.google.com', maps: 'https://maps.google.com', whatsapp: 'https://web.whatsapp.com', instagram: 'https://instagram.com', github: 'https://github.com', spotify: 'https://open.spotify.com', netflix: 'https://netflix.com', linkedin: 'https://linkedin.com', chatgpt: 'https://chatgpt.com', claude: 'https://claude.ai', twitter: 'https://x.com', x: 'https://x.com', facebook: 'https://facebook.com' };

// ---------------- helpers ----------------
const clean = (s) => String(s || '').replace(/[*`#]/g, '').trim();
const today = () => new Date().toISOString().slice(0, 10);
let saveT = null;
function saveSoon() { clearTimeout(saveT); saveT = setTimeout(() => { omen.store.set('chat', chat.slice(-80)); omen.store.set('usage', usage); }, 500); }
function logErr(m) { omen.log('error', m); }
function setFace(state, label) {
  $('#face').className = state;
  $('#status').textContent = label || ({ idle: 'READY', listening: 'LISTENING', thinking: 'THINKING', speaking: 'SPEAKING' }[state] || '');
}
function linkify(el, text) {
  const re = /https?:\/\/[^\s)]+/g; let last = 0, m;
  while ((m = re.exec(text))) {
    el.appendChild(document.createTextNode(text.slice(last, m.index)));
    const a = document.createElement('a'); a.textContent = m[0]; a.onclick = () => omen.openExternal(m[0]); el.appendChild(a); last = m.index + m[0].length;
  }
  el.appendChild(document.createTextNode(text.slice(last)));
}
function imgEl(url) { const i = document.createElement('img'); i.src = url; i.onclick = () => { $('#lightbox img').src = url; $('#lightbox').style.display = 'flex'; }; return i; }
function bubble(m) {
  const d = document.createElement('div'); d.className = 'b ' + (m.u ? 'u' : 'o');
  if (m.kind === 'img' || m.kind === 'gal') { const r = document.createElement('div'); r.className = 'row'; (m.urls || []).forEach((u) => r.appendChild(imgEl(u))); d.appendChild(r); }
  if (m.kind === 'doc') { const c = document.createElement('div'); c.className = 'doc'; c.textContent = '📄 ' + m.names; d.appendChild(c); }
  if (m.t) { const s = document.createElement('div'); linkify(s, m.t); d.appendChild(s); }
  return d;
}
function renderChat() {
  const c = $('#chat'); c.innerHTML = '';
  if (!chat.length) c.appendChild(bubble({ u: false, t: 'Hello. Type below, drop a file, or hold the mic. Press Ctrl+Shift+Space anywhere to talk to me.' }));
  chat.forEach((m) => c.appendChild(bubble(m)));
  c.scrollTop = c.scrollHeight;
}
function add(m) { chat.push(m); saveSoon(); $('#chat').appendChild(bubble(m)); $('#chat').scrollTop = $('#chat').scrollHeight; if (m.u) pushHistory(m.t); }
async function pushHistory(t) {
  if (!t) return; const h = (await omen.store.get('history')) || [];
  const i = h.findIndex((x) => x.toLowerCase() === t.toLowerCase()); if (i >= 0) h.splice(i, 1);
  h.push(t); await omen.store.set('history', h.slice(-200)); renderRecents();
}
async function renderRecents() {
  const h = ((await omen.store.get('history')) || []).slice().reverse(); const r = $('#recents'); r.innerHTML = '';
  h.forEach((t) => { const d = document.createElement('div'); d.className = 'rec'; d.textContent = '💬  ' + t; d.onclick = () => { $('#input').value = t; $('#input').focus(); }; r.appendChild(d); });
}

// ---------------- voice out ----------------
function speak(text) {
  if (!S.voice || !text) return;
  try {
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(String(text).slice(0, 600));
    const vs = speechSynthesis.getVoices();
    u.voice = vs.find((v) => v.name === S.voiceName) || vs.find((v) => /en-GB/i.test(v.lang)) || vs.find((v) => /^en/i.test(v.lang)) || null;
    u.pitch = S.pitch; u.rate = S.rate;
    u.onstart = () => setFace('speaking'); u.onend = () => setFace('idle');
    speechSynthesis.speak(u);
  } catch (e) {}
}
function say(text, o = {}) { add({ u: false, t: text }); if (o.speak !== false) speak(text); else setFace('idle'); }

// ---------------- Groq ----------------
function noteUsage(r, body, j) {
  if (usage.date !== today()) usage = { date: today(), requests: 0, errors: 0, promptTokens: 0, completionTokens: 0, byModel: {}, total: usage.total || 0, limits: {} };
  usage.requests++; usage.total = (usage.total || 0) + 1;
  if (!r.ok) usage.errors++;
  try {
    if (j && j.usage) { usage.promptTokens += j.usage.prompt_tokens || 0; usage.completionTokens += j.usage.completion_tokens || 0; }
    const mdl = body && JSON.parse(body).model; if (mdl) usage.byModel[mdl] = (usage.byModel[mdl] || 0) + 1;
  } catch (e) {}
  Object.keys(r.headers || {}).filter((k) => k.startsWith('x-ratelimit') || k === 'retry-after').forEach((k) => { usage.limits[k] = r.headers[k]; });
  usage.lastAt = new Date().toISOString(); saveSoon();
}
async function api(path, method, body) {
  if (!S.key) throw new Error('Add your Groq key in the Admin panel (gear icon) first');
  const payload = body ? JSON.stringify(body) : undefined;
  const r = await omen.http({ url: 'https://api.groq.com/openai/v1' + path, method, headers: { Authorization: 'Bearer ' + S.key, 'Content-Type': 'application/json' }, body: payload });
  let j = null; try { j = JSON.parse(r.text); } catch (e) {}
  noteUsage(r, payload, j);
  if (r.error) { logErr('network: ' + r.error); throw new Error('No internet or Groq unreachable (' + r.error + ')'); }
  if (!r.ok || (j && j.error)) { const msg = (j && j.error && j.error.message) || ('HTTP ' + r.status); logErr('Groq ' + path + ': ' + msg); throw new Error(msg); }
  return j;
}
async function loadModels() { const j = await api('/models', 'GET'); models = j.data.map((m) => m.id); pickModels(); return models; }
function pickModels() {
  const ok = (id) => models.includes(id) && !bad.has(id);
  brainM = (S.model && ok(S.model)) ? S.model : (PREF.find(ok) || models.find((id) => ok(id) && !NONCHAT.test(id)) || '');
  webM = ['groq/compound', 'groq/compound-mini'].find(ok) || '';
}
async function llm(messages, maxTokens = 600, extra = {}) {
  for (let i = 0; i < 6; i++) {
    if (!brainM) await loadModels();
    if (!brainM) throw new Error('No Groq chat model is available for this key');
    try { const j = await api('/chat/completions', 'POST', { model: brainM, messages, max_tokens: maxTokens, temperature: 0.4, ...extra }); return j.choices[0].message; }
    catch (e) { if (/does not exist|do not have access|not found|decommission/i.test(e.message)) { bad.add(brainM); pickModels(); } else throw e; }
  }
  throw new Error('No working Groq model found');
}
const googleRef = (q) => ['Search this on Google', 'https://www.google.com/search?q=' + encodeURIComponent(q)];
function refsFrom(msg) {
  const out = [];
  (msg.executed_tools || []).forEach((t) => ((t.search_results && t.search_results.results) || []).forEach((r) => { if (r.url && /^http/.test(r.url) && !out.some((o) => o[1] === r.url)) out.push([r.title || r.url, r.url]); }));
  return out.slice(0, 5);
}
function withRefs(text, refs) { return text + (refs.length ? '\n\nReferences:\n' + refs.map((r, i) => (i + 1) + '. ' + r[0] + '\n' + r[1]).join('\n') : ''); }
async function research(q) {
  if (!models.length) await loadModels();
  const msgs = [{ role: 'system', content: 'You are OMEN, a calm shadowy research assistant. Right now it is ' + new Date().toLocaleString() + '. Search the web, then answer in plain text under 150 words. No markdown, lists or links.' }, { role: 'user', content: q }];
  const tries = [];
  ['groq/compound', 'groq/compound-mini'].filter((m) => models.includes(m)).forEach((m) => tries.push({ model: m, max_tokens: 900 }));
  ['openai/gpt-oss-120b', 'openai/gpt-oss-20b'].filter((m) => models.includes(m)).forEach((m) => tries.push({ model: m, tools: [{ type: 'browser_search' }], max_tokens: 2000 }));
  for (const t of tries) {
    try { const j = await api('/chat/completions', 'POST', { messages: msgs, temperature: 0.4, ...t }); const msg = j.choices[0].message; if (msg.content) return { text: clean(msg.content), refs: refsFrom(msg).concat([googleRef(q)]) }; } catch (e) {}
  }
  return wikiFallback(q, msgs);
}
async function getText(url) { const r = await omen.http({ url, headers: { 'User-Agent': 'Mozilla/5.0 OMEN/1.0' }, timeout: 20000 }); if (!r.ok) throw new Error('HTTP ' + r.status); return r.text; }
async function wikiFallback(q, base) {
  let ctxText = ''; const refs = [];
  try {
    const arr = JSON.parse(await getText('https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=' + encodeURIComponent(q))).query.search;
    arr.forEach((o, i) => { ctxText += '[' + (i + 1) + '] ' + o.title + ': ' + o.snippet.replace(/<[^>]+>/g, '') + '\n'; refs.push([o.title, 'https://en.wikipedia.org/wiki/' + encodeURIComponent(o.title.replace(/ /g, '_'))]); });
  } catch (e) {}
  const m = await llm([base[0], { role: 'user', content: 'Question: ' + q + '\n\nSources:\n' + ctxText + '\nAnswer briefly using these sources if relevant, otherwise from your own knowledge.' }], 500);
  return { text: clean(m.content) + ' Live web search is not on your Groq account, so this used Wikipedia and my own knowledge.', refs: refs.concat([googleRef(q)]) };
}
async function showResearch(r) { add({ u: false, t: withRefs(r.text, r.refs) }); speak(r.text); }
async function searchWeb(q) { add({ u: false, t: 'Searching the web…' }); setFace('thinking'); await showResearch(await research(q)); }
async function readLink(url, task) {
  add({ u: false, t: 'Reading the page…' }); setFace('thinking');
  const ct = (await getText(url)).replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<!--[\s\S]*?-->/g, ' ').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim().slice(0, 14000);
  if (ct.length < 80) throw new Error('The page had no readable text');
  const m = await llm([{ role: 'user', content: 'Text of the web page ' + url + ':\n<<<\n' + ct + '\n>>>\nTask: ' + task + '\nAnswer in plain text under 150 words, using only this page.' }], 700);
  await showResearch({ text: clean(m.content), refs: [[url, url]] });
}
async function genImage(prompt) {
  add({ u: false, t: 'Creating your image… this can take up to a minute.' }); setFace('thinking');
  const r = await omen.image({ url: 'https://image.pollinations.ai/prompt/' + encodeURIComponent(prompt) + '?width=1024&height=1024&nologo=true&seed=' + (Date.now() % 100000), headers: { 'User-Agent': 'OMEN/1.0' } });
  if (!r.ok) throw new Error('The image service is busy: ' + r.error);
  add({ u: false, kind: 'img', urls: [r.url], t: prompt }); speak('Your image is ready.');
}
async function imgSearch(q) {
  add({ u: false, t: 'Searching images…' }); setFace('thinking');
  const j = JSON.parse(await getText('https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrlimit=10&prop=imageinfo&iiprop=url%7Cmime&iiurlwidth=600&format=json&gsrsearch=' + encodeURIComponent(q + ' filetype:bitmap')));
  const pages = Object.values((j.query && j.query.pages) || {});
  const urls = pages.map((p) => p.imageinfo && p.imageinfo[0]).filter((i) => i && /^image\/(jpeg|png)/.test(i.mime)).map((i) => i.thumburl || i.url).slice(0, 8);
  const got = [];
  for (const u of urls) { const r = await omen.image({ url: u }); if (r.ok) got.push(r.url); if (got.length >= 6) break; }
  if (!got.length) return say('I found no images of ' + q + '.');
  add({ u: false, kind: 'gal', urls: got, t: 'Images of ' + q + ' (Wikimedia Commons)' }); speak('Here are images of ' + q + '.');
}
async function vision(prompt, b64s, mimes) {
  if (!models.length) await loadModels();
  const usable = (id) => !bad.has(id) && !visBad.has(id) && !NONCHAT.test(id);
  const cands = [];
  if (S.visionModel && models.includes(S.visionModel) && usable(S.visionModel)) cands.push(S.visionModel);
  models.filter((m) => usable(m) && VISHINT.test(m) && !cands.includes(m)).forEach((m) => cands.push(m));
  models.filter((m) => usable(m) && !cands.includes(m)).slice(0, 3).forEach((m) => cands.push(m));
  const call = async (m, list) => {
    const content = [{ type: 'text', text: prompt }].concat(list.map((b, i) => ({ type: 'image_url', image_url: { url: 'data:' + (mimes[i] || 'image/jpeg') + ';base64,' + b } })));
    const j = await api('/chat/completions', 'POST', { model: m, messages: [{ role: 'user', content }], max_tokens: 1800, temperature: 0.2 });
    return clean(j.choices[0].message.content);
  };
  let last = '';
  for (const m of cands.slice(0, 6)) {
    for (let a = 0; a < 2; a++) {
      try { const out = await call(m, b64s); if (out) { if (S.visionModel !== m) { S.visionModel = m; omen.setSettings({ visionModel: m }); } return out; } break; }
      catch (e) {
        last = m.split('/').pop() + ': ' + e.message.slice(0, 160);
        if (/rate limit|try again|quota/i.test(e.message) && a === 0) { await new Promise((r) => setTimeout(r, 6000)); continue; }
        if (b64s.length > 1 && /too large|payload|413|exceed|maximum/i.test(e.message)) { const parts = []; for (let i = 0; i < b64s.length; i++) parts.push('--- page ' + (i + 1) + ' ---\n' + await call(m, [b64s[i]])); return parts.join('\n\n'); }
        if (!/rate limit|try again|quota/i.test(e.message)) visBad.add(m);
        break;
      }
    }
  }
  throw new Error('No Groq model accepted the photo. Last error: ' + last);
}

// ---------------- commands ----------------
const looksLikeQuestion = (t) => !/joke|hello|how are you|your name|thank|good (morning|night|evening)/.test(t) && (t.endsWith('?') || /^(who|what|when|where|why|how|which|is|are|was|were|do|does|did|can|will|should|tell|give|explain|latest|news|weather|price|score|define|meaning)\b/.test(t));
const NUM = { one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, fifteen: 15, twenty: 20, thirty: 30 };
function timerFor(ms, label) { setTimeout(() => { omen.notify('OMEN', label); say(label); }, ms); }

async function route(raw) {
  let t = raw.toLowerCase().trim().replace(/\b(one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty)\b/g, (w) => NUM[w]);
  let m;
  if (/\b(what('s| is)? the time|what time is it|time now|current time)\b/.test(t)) return say('It is ' + new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + '.');
  if (/\b(today'?s date|what('s| is)? (the )?date|what day is it)\b/.test(t)) return say('Today is ' + new Date().toLocaleDateString([], { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' }) + '.');
  if ((m = raw.match(/https?:\/\/\S+/))) return readLink(m[0].replace(/[.,)]+$/, ''), raw.replace(m[0], '').trim() || 'Summarize this page.');
  if ((m = t.match(/timer.*?(\d+(?:\.\d+)?)\s*(sec|min|hour|hr)/) || t.match(/(\d+(?:\.\d+)?)\s*(sec|min|hour|hr)\w*\s+timer/))) {
    const n = parseFloat(m[1]), u = m[2][0], ms = n * (u === 'h' ? 3600000 : u === 'm' ? 60000 : 1000);
    timerFor(ms, 'Your ' + m[1] + ' ' + m[2] + ' timer is complete.'); return say('Timer set for ' + m[1] + ' ' + m[2] + '. Keep OMEN running in the tray.');
  }
  if (!/\bin\s+\d/.test(t) && (m = t.match(/(?:alarm|wake me|remind me).*?(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?/))) {
    let h = +m[1]; const mi = +(m[2] || 0), p = (m[3] || '')[0];
    if (p === 'p' && h < 12) h += 12; if (p === 'a' && h === 12) h = 0;
    const d = new Date(); d.setHours(h, mi, 0, 0); if (d <= new Date()) d.setDate(d.getDate() + 1);
    timerFor(d - new Date(), 'Sir, it is ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + '. Your alarm.');
    return say('Alarm set for ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + '. Keep OMEN running in the tray.');
  }
  if ((m = t.match(/^(?:generate|create|make|draw|paint|render|design)(?:\s+me)?(?:\s+an?)?\s+(?:image|picture|photo|logo|poster|wallpaper|illustration|artwork|drawing|painting)\s*(?:of|for|showing|with|about)?\s*(.+)/) || t.match(/^(?:imagine|draw|paint)\s+(.+)/))) return genImage(m[1]);
  if ((m = t.match(/^(?:show|find|get|search|give|display)(?:\s+me)?(?:\s+(?:some|a few))?\s+(?:images?|pictures?|photos?|pics?)\s+(?:of|for)\s+(.+)/) || t.match(/^(?:images?|pictures?|photos?|pics?)\s+(?:of|for)\s+(.+)/))) return imgSearch(m[1]);
  if ((m = t.match(/^(?:take a note|make a note|add a note|note down|note)\s*(?:that|to)?[:,]?\s+(.+)/))) { const n = (await omen.store.get('notes')) || []; n.push(m[1]); await omen.store.set('notes', n); return say('Noted.'); }
  if (/\b(read|show|list|what are)( me)?( my)? notes\b/.test(t)) { const n = (await omen.store.get('notes')) || []; return say(n.length ? 'Your notes. ' + n.slice(-8).map((x, i) => (i + 1) + '. ' + x).join('. ') : 'You have no notes.'); }
  if ((m = t.match(/^(?:navigate to|directions to|take me to|route to)\s+(.+)/))) { omen.openExternal('https://www.google.com/maps/search/' + encodeURIComponent(m[1])); return say('Opening ' + m[1] + ' on the map.'); }
  if ((m = t.match(/^play\s+(.+?)(?:\s+on\s+(youtube|spotify))?$/))) { omen.openExternal(m[2] === 'spotify' ? 'https://open.spotify.com/search/' + encodeURIComponent(m[1]) : 'https://www.youtube.com/results?search_query=' + encodeURIComponent(m[1])); return say('Searching ' + m[1] + (m[2] ? ' on ' + m[2] : ' on YouTube') + '.'); }
  if ((m = t.match(/^(?:please\s+)?(?:open|launch|start)\s+(.+)/))) {
    const name = m[1].replace(/\b(app|website|site)\b/g, '').trim();
    if (SITES[name]) { omen.openExternal(SITES[name]); return say('Opening ' + name + '.'); }
    const r = await omen.openApp(name); return say(r.ok ? 'Opening ' + name + '.' : (r.error || 'I could not open ' + name + '.'));
  }
  if ((m = t.match(/^(?:search for|search|look up|google|research|find out about|find out|tell me about)\s+(.+)/))) return searchWeb(m[1]);
  if (webMode || looksLikeQuestion(t)) return searchWeb(raw);
  const msgs = [{ role: 'system', content: 'You are OMEN, a calm, shadowy, loyal personal assistant inspired by the Valorant character. Speak in short, low-key, slightly mysterious sentences. Plain text only. Now: ' + new Date().toString() + '.' }].concat(ctx.slice(-10), [{ role: 'user', content: raw }]);
  setFace('thinking');
  const a = await llm(msgs, 500); const txt = clean(a.content);
  ctx.push({ role: 'user', content: raw }, { role: 'assistant', content: txt }); say(txt);
}

// ---------------- attachments ----------------
const NOTES = 'These are photos of the user\'s notes (handwritten or printed). Read them carefully and in order. Use plain text only: no markdown, no asterisks; use simple dashes and numbers. ';
const MODES = {
  read: 'Transcribe all the text exactly as written, keeping headings, bullet points, numbering, formulas and table layout. Write [illegible] for words you cannot read. Output only the transcription.',
  summary: 'Write a clear summary: first a one-line topic, then the main points as short dashed lines. Fix obvious spelling slips.',
  key: 'List the key terms with short definitions, and the important facts and formulas. Then give 3 likely exam questions.',
  quiz: 'Create 6 quiz questions from these notes (mix of short answer and multiple choice), each followed by a brief answer.',
  default: 'First transcribe the notes accurately, then add a section titled SUMMARY with the key points.',
};
const LABEL = { read: 'Read text', summary: 'Summarize', key: 'Key points', quiz: 'Quiz me', default: 'Read and summarize' };
function drawAttach() {
  const has = attach.images.length || attach.docs.length; $('#attach').style.display = has ? 'block' : 'none';
  const it = $('#items'); it.innerHTML = '';
  attach.images.forEach((i) => { const im = document.createElement('img'); im.src = i.url; it.appendChild(im); });
  attach.docs.forEach((d) => { const c = document.createElement('span'); c.className = 'chip'; c.textContent = '📄 ' + d.name; it.appendChild(c); });
  const x = document.createElement('button'); x.className = 'cb'; x.textContent = '✕'; x.onclick = () => { attach.images = []; attach.docs = []; drawAttach(); }; it.appendChild(x);
  const acts = $('#acts'); acts.innerHTML = '';
  const list = attach.images.length ? ['read', 'summary', 'key', 'quiz'] : ['summary', 'key', 'quiz'];
  list.forEach((k) => { const a = document.createElement('div'); a.className = 'act'; a.textContent = LABEL[k]; a.onclick = () => sendAttach(k, $('#input').value.trim()); acts.appendChild(a); });
}
async function addFiles(paths) {
  if (!paths.length) return;
  const r = await omen.readFiles(paths);
  r.files.forEach((f) => { if (f.kind === 'image') { if (attach.images.length < 5) attach.images.push(f); } else attach.docs.push(f); });
  if (r.errors.length) say(r.errors.join('\n'), { speak: false });
  drawAttach();
}
async function sendAttach(mode, typed) {
  const imgs = attach.images.slice(), docs = attach.docs.slice(); if (!imgs.length && !docs.length) return;
  if (typed && mode === 'default') mode = 'custom';
  const label = mode === 'custom' ? typed : LABEL[mode] || 'Summarize';
  attach.images = []; attach.docs = []; drawAttach(); $('#input').value = '';
  if (imgs.length) add({ u: true, kind: imgs.length > 1 ? 'gal' : 'img', urls: imgs.map((i) => i.url), t: label });
  if (docs.length) add({ u: true, kind: 'doc', names: docs.map((d) => d.name).join(', '), t: label });
  setFace('thinking');
  let ans;
  if (imgs.length) {
    const extra = docs.length ? '\n\nAlso use this attached document text:\n' + docs.map((d) => d.name + ':\n' + d.text).join('\n\n').slice(0, 8000) : '';
    ans = await vision(NOTES + (mode === 'custom' ? 'Do this: ' + typed : MODES[mode] || MODES.default) + extra, imgs.map((i) => i.b64), imgs.map((i) => i.mime));
  } else {
    const task = mode === 'custom' ? typed : mode === 'key' ? 'List the key terms with short definitions, and the important facts, dates and numbers. Then give 3 likely exam questions.' : mode === 'quiz' ? 'Create 6 quiz questions from this document (mix of short answer and multiple choice), each followed by a brief answer.' : 'Summarize this document: first a one-line topic, then the main points as short dashed lines, then any dates, numbers, names or action items.';
    const per = Math.floor(24000 / docs.length);
    const m = await llm([{ role: 'user', content: 'The user shared these documents:\n' + docs.map((d) => 'FILE: ' + d.name + '\n<<<\n' + d.text.slice(0, per) + '\n>>>').join('\n\n') + '\n\nRequest: ' + task + '\nUse plain text only: no markdown, no asterisks; simple dashes and numbers.' }], 1500);
    ans = clean(m.content);
  }
  say(ans, { speak: ans.length <= 500 });
}

// ---------------- sending ----------------
let busy = false;
async function send(text) {
  text = (text || '').trim();
  if (attach.images.length || attach.docs.length) return sendAttach(text ? 'custom' : 'default', text).catch(fail);
  if (!text || busy) return;
  $('#input').value = ''; autosize(); busy = true;
  add({ u: true, t: text });
  try { await route(text); } catch (e) { fail(e); } finally { busy = false; if (!speechSynthesis.speaking) setFace('idle'); }
}
function fail(e) { logErr(String(e.message || e)); say('Something broke: ' + (e.message || e), { speak: false }); }

// ---------------- microphone (Groq Whisper) ----------------
async function toggleRecord() {
  if (recording) { rec.stop(); return; }
  if (!S.key) return say('Add your Groq key in the Admin panel first.', { speak: false });
  let stream; try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); } catch (e) { return say('The microphone is blocked. Allow it in your system privacy settings.', { speak: false }); }
  const chunks = []; rec = new MediaRecorder(stream, { mimeType: 'audio/webm' });
  rec.ondataavailable = (e) => chunks.push(e.data);
  rec.onstop = async () => {
    stream.getTracks().forEach((t) => t.stop()); recording = false; $('#bMic').classList.remove('rec'); setFace('thinking', 'TRANSCRIBING');
    const buf = new Uint8Array(await new Blob(chunks, { type: 'audio/webm' }).arrayBuffer()); let s = ''; for (let i = 0; i < buf.length; i += 8192) s += String.fromCharCode.apply(null, buf.subarray(i, i + 8192));
    const r = await omen.transcribe({ b64: btoa(s), mime: 'audio/webm', key: S.key });
    if (!r.ok) { logErr('transcribe: ' + r.error); setFace('idle'); return say('I could not hear that: ' + r.error, { speak: false }); }
    if (!r.text) { setFace('idle'); return; }
    send(r.text);
  };
  rec.start(); recording = true; $('#bMic').classList.add('rec'); setFace('listening');
  setTimeout(() => { if (recording) rec.stop(); }, 25000);
}

// ---------------- UI wiring ----------------
function autosize() { const i = $('#input'); i.style.height = 'auto'; i.style.height = Math.min(130, i.scrollHeight) + 'px'; }
function applySettings() {
  document.documentElement.style.setProperty('--glass', (S.glass || 28) / 100);
  const w = $('#wind'); if (S.wind) { w.style.display = 'block'; w.play().catch(() => {}); $('#shade').style.display = 'block'; } else { w.pause(); w.style.display = 'none'; $('#shade').style.display = 'none'; }
  const ini = (S.name || '').trim().split(/\s+/).filter(Boolean).slice(0, 2).map((x) => x[0].toUpperCase()).join('') || 'OM'; $('#avatar').textContent = ini;
}
function plusMenu() {
  const p = $('#plus'); if (p.style.display === 'block') { p.style.display = 'none'; return; }
  const items = [['🖼️  Photos', async () => addFiles(await omen.pickFiles())], ['📎  Files and documents', async () => addFiles(await omen.pickFiles())],
    ['🌐  Web search: ' + (webMode ? 'ON' : 'OFF'), () => setWeb(!webMode)], ['🔊  Spoken replies: ' + (S.voice ? 'ON' : 'OFF'), () => omen.setSettings({ voice: !S.voice })],
    ['🎨  Create an image', () => { $('#input').value = 'Create an image of '; $('#input').focus(); }]];
  p.innerHTML = ''; items.forEach(([l, f]) => { const d = document.createElement('div'); d.textContent = l; d.onclick = () => { p.style.display = 'none'; f(); }; p.appendChild(d); }); p.style.display = 'block';
}
function setWeb(on) { webMode = on; const b = $('#mode'); b.textContent = on ? 'Web search' : 'Chat'; b.classList.toggle('on', on); }

async function init() {
  S = await omen.getSettings(); chat = (await omen.store.get('chat')) || []; usage = (await omen.store.get('usage')) || {};
  applySettings(); renderChat(); renderRecents();
  omen.on('settings:changed', (s) => { S = s; pickModels(); applySettings(); });
  omen.on('hotkey:talk', () => toggleRecord());
  $('#burger').onclick = () => $('#side').classList.toggle('hide');
  $('#gear').onclick = $('#nAdmin').onclick = () => omen.openAdmin();
  $('#avatar').onclick = () => omen.openAdmin();
  $('#nChats').onclick = () => $('#input').focus();
  $('#nNotes').onclick = () => send('read my notes');
  $('#nFile').onclick = async () => addFiles(await omen.pickFiles());
  $('#newChat').onclick = async () => { chat = []; await omen.store.set('chat', []); renderChat(); };
  $('#mode').onclick = () => setWeb(!webMode);
  $('#bPlus').onclick = plusMenu;
  $('#bMic').onclick = toggleRecord;
  $('#bGo').onclick = () => ($('#input').value.trim() || attach.images.length || attach.docs.length ? send($('#input').value) : toggleRecord());
  $('#input').addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send($('#input').value); } });
  $('#input').addEventListener('input', autosize);
  $('#input').addEventListener('paste', async (e) => {
    const it = [...(e.clipboardData.items || [])].find((x) => x.type.startsWith('image/')); if (!it) return;
    e.preventDefault(); const b = it.getAsFile(); const ab = new Uint8Array(await b.arrayBuffer()); let s = ''; for (let i = 0; i < ab.length; i += 8192) s += String.fromCharCode.apply(null, ab.subarray(i, i + 8192));
    const r = await omen.saveImage({ b64: btoa(s) }); attach.images.push({ ...r, mime: b.type, name: 'pasted image' }); drawAttach();
  });
  $('#lightbox').onclick = () => { $('#lightbox').style.display = 'none'; };
  document.addEventListener('click', (e) => { if (!e.target.closest('#plus') && !e.target.closest('#bPlus')) $('#plus').style.display = 'none'; });
  let dragN = 0;
  window.addEventListener('dragenter', (e) => { e.preventDefault(); dragN++; $('#drop').style.display = 'flex'; });
  window.addEventListener('dragleave', () => { if (--dragN <= 0) { dragN = 0; $('#drop').style.display = 'none'; } });
  window.addEventListener('dragover', (e) => e.preventDefault());
  window.addEventListener('drop', (e) => { e.preventDefault(); dragN = 0; $('#drop').style.display = 'none'; addFiles([...e.dataTransfer.files].map((f) => f.path).filter(Boolean)); });
  if (!S.key) say('Welcome. Open the Admin panel (gear, top right) and paste your Groq API key to begin.', { speak: false });
  else loadModels().catch((e) => logErr('models: ' + e.message));
  setFace('idle');
}
init();
