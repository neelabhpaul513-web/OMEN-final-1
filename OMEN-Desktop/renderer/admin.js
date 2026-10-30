'use strict';
const $ = (s) => document.querySelector(s);
let S = {}, tab = 'overview', info = {}, models = [], testMsg = '', timer = null;
const TABS = [['overview', '📊  Overview'], ['ai', '🧠  AI and models'], ['voice', '🔊  Voice'], ['look', '🎨  Appearance'], ['behave', '⚙️  Behaviour'], ['usage', '📈  Usage and limits'], ['logs', '🧾  Logs'], ['data', '💾  Data and backup'], ['security', '🔒  Security'], ['about', 'ℹ️  About']];
const mask = (k) => (k ? k.slice(0, 7) + '••••••••' + k.slice(-3) : '(not set)');
const fmtMB = (b) => (b / 1048576).toFixed(0) + ' MB';

function el(tag, props = {}, kids = []) {
  const e = document.createElement(tag); Object.assign(e, props);
  (Array.isArray(kids) ? kids : [kids]).forEach((k) => e.appendChild(typeof k === 'string' ? document.createTextNode(k) : k)); return e;
}
function row(label, hint, control) { return el('div', { className: 'row' }, [el('div', { className: 'l' }, [el('b', { textContent: label }), el('span', { textContent: hint || '' })]), control]); }
function card(rows) { return el('div', { className: 'card' }, rows); }
function toggle(key, label, hint) { const s = el('div', { className: 'sw' + (S[key] ? ' on' : '') }); s.onclick = async () => { S = await omen.setSettings({ [key]: !S[key] }); s.classList.toggle('on', !!S[key]); }; return row(label, hint, s); }
function text(key, label, hint, type = 'text') { const i = el('input', { type, value: S[key] || '' }); i.onchange = async () => { S = await omen.setSettings({ [key]: i.value.trim() }); }; return row(label, hint, i); }
function slider(key, label, hint, min, max, step) {
  const val = el('span', { textContent: String(S[key]) }); const r = el('input', { type: 'range', min, max, step, value: S[key] });
  r.oninput = () => { val.textContent = r.value; }; r.onchange = async () => { S = await omen.setSettings({ [key]: parseFloat(r.value) }); };
  return row(label, hint, el('div', {}, [r, el('div', { style: 'text-align:right;color:#9a9aa2', textContent: '' }, [val])]));
}
function stat(label, value, cls) { return el('div', { className: 'stat' }, [el('small', { textContent: label }), el('b', { className: cls || '', textContent: String(value) })]); }
function btn(label, f, cls) { return el('button', { textContent: label, className: cls || '', onclick: f }); }
function head(t, s) { return [el('h1', { textContent: t }), el('div', { className: 'sub', textContent: s })]; }

async function testKey() {
  testMsg = 'Testing…'; render();
  const r = await omen.http({ url: 'https://api.groq.com/openai/v1/models', headers: { Authorization: 'Bearer ' + S.key } });
  let j = null; try { j = JSON.parse(r.text); } catch (e) {}
  if (r.ok && j) { models = j.data.map((m) => m.id).sort(); testMsg = 'Connected ✔  ' + models.length + ' models available'; }
  else { models = []; testMsg = 'Failed: ' + ((j && j.error && j.error.message) || r.error || ('HTTP ' + r.status)); }
  render();
}
function roles(id) {
  const out = [];
  if (/whisper/i.test(id)) out.push('speech to text'); else if (/tts|orpheus|playai/i.test(id)) out.push('text to speech');
  else if (/guard|safeguard/i.test(id)) out.push('safety'); else if (/compound/i.test(id)) out.push('web search'); else out.push('chat');
  if (/vision|vl\b|llama-4|scout|maverick|pixtral|llava|gemma-3/i.test(id)) out.push('images');
  if (id === S.model) out.push('★ your pick'); if (id === S.visionModel) out.push('★ photo model');
  return out.join(', ');
}

async function render() {
  const nav = $('#nav'); [...nav.querySelectorAll('.t')].forEach((t) => t.remove());
  TABS.forEach(([k, l]) => nav.appendChild(el('div', { className: 't' + (k === tab ? ' on' : ''), textContent: l, onclick: () => { tab = k; render(); } })));
  const b = $('#body'); b.innerHTML = '';
  const usage = (await omen.store.get('usage')) || {};
  if (tab === 'overview') {
    const st = await omen.sysStats(); const lim = usage.limits || {};
    head('Overview', 'Live status of OMEN on this computer').forEach((x) => b.appendChild(x));
    b.appendChild(el('div', { className: 'grid' }, [
      stat('Groq key', S.key ? 'set' : 'missing', S.key ? 'ok' : 'err'), stat('Chat model', S.model || 'automatic'), stat('Photo model', S.visionModel || 'learns on first photo'),
      stat('Requests today', usage.date === new Date().toISOString().slice(0, 10) ? usage.requests || 0 : 0), stat('Errors today', usage.errors || 0, usage.errors ? 'warn' : ''),
      stat('Tokens used today', ((usage.promptTokens || 0) + (usage.completionTokens || 0)).toLocaleString()),
      stat('Tokens left this minute', lim['x-ratelimit-remaining-tokens'] || 'n/a'), stat('Requests left today', lim['x-ratelimit-remaining-requests'] || 'n/a'),
      stat('Talk hotkey', S.hotkey || 'off'), stat('Memory used by OMEN', fmtMB(st.rss)), stat('Free system memory', fmtMB(st.freeMem) + ' / ' + fmtMB(st.totalMem)), stat('Admin PIN', S.adminPin ? 'on' : 'off', S.adminPin ? 'ok' : 'warn'),
    ]));
  }
  if (tab === 'ai') {
    head('AI and models', 'The brain behind OMEN is Groq').forEach((x) => b.appendChild(x));
    const keyBox = el('input', { type: 'password', value: S.key || '' }); keyBox.onchange = async () => { S = await omen.setSettings({ key: keyBox.value.trim() }); };
    const show = btn('Show', () => { keyBox.type = keyBox.type === 'password' ? 'text' : 'password'; show.textContent = keyBox.type === 'password' ? 'Show' : 'Hide'; });
    b.appendChild(card([row('Groq API key', 'Stored only on this computer. Currently: ' + mask(S.key), el('div', {}, [keyBox, ' ', show])),
      text('name', 'Your name', 'Shown as initials in the side menu'),
      text('model', 'Chat model override', 'Leave empty for automatic. Example: llama-3.3-70b-versatile'),
      text('visionModel', 'Photo model override', 'Leave empty: OMEN finds one that accepts images and remembers it'),
      row('Test the connection', testMsg, btn('Test key and list models', testKey))]));
    if (models.length) b.appendChild(card([el('table', {}, [el('tr', {}, [el('th', { textContent: 'Model' }), el('th', { textContent: 'Used for' })]), ...models.map((m) => el('tr', {}, [el('td', { textContent: m }), el('td', { textContent: roles(m) })]))])]));
  }
  if (tab === 'voice') {
    head('Voice', 'How OMEN sounds and how you talk to it').forEach((x) => b.appendChild(x));
    const sel = el('select'); const vs = speechSynthesis.getVoices(); sel.appendChild(el('option', { value: '', textContent: 'Automatic (deep British if available)' }));
    vs.forEach((v) => sel.appendChild(el('option', { value: v.name, textContent: v.name + ' (' + v.lang + ')', selected: v.name === S.voiceName }))); sel.onchange = async () => { S = await omen.setSettings({ voiceName: sel.value }); };
    b.appendChild(card([toggle('voice', 'Speak replies aloud', 'Turn off for silent text answers'), row('Voice', 'Pick any voice installed on Windows', sel),
      slider('pitch', 'Pitch', 'Lower is deeper (OMEN default 0.45)', 0.1, 2, 0.05), slider('rate', 'Speed', 'Lower is slower (OMEN default 0.85)', 0.5, 1.5, 0.05),
      row('Test the voice', '', btn('Say something', () => { const u = new SpeechSynthesisUtterance('What can I do for you, sir?'); u.pitch = S.pitch; u.rate = S.rate; const v = vs.find((x) => x.name === S.voiceName) || vs.find((x) => /en-GB/i.test(x.lang)); if (v) u.voice = v; speechSynthesis.cancel(); speechSynthesis.speak(u); })),
      text('hotkey', 'Talk hotkey', 'Works from any app. Format: CommandOrControl+Shift+Space')]));
    b.appendChild(el('div', { className: 'sub', textContent: 'Speech to text uses Groq Whisper, so it needs your key and internet.' }));
  }
  if (tab === 'look') {
    head('Appearance', 'Background and chat transparency').forEach((x) => b.appendChild(x));
    b.appendChild(card([toggle('wind', 'Animated black-wind background', 'Looping 2K video. Turn off to save battery and GPU'), slider('glass', 'Chat bubble opacity', 'Lower = more transparent, so Omen\'s face shows through', 0, 80, 1)]));
  }
  if (tab === 'behave') {
    head('Behaviour', 'How OMEN lives on your computer').forEach((x) => b.appendChild(x));
    b.appendChild(card([toggle('startup', 'Start OMEN when I sign in', 'Opens quietly in the tray'), toggle('tray', 'Keep running in the tray when I close the window', 'Needed for timers, alarms and the talk hotkey')]));
  }
  if (tab === 'usage') {
    head('Usage and limits', 'Counted by this app (resets daily) and read from Groq\'s response headers').forEach((x) => b.appendChild(x));
    b.appendChild(el('div', { className: 'grid' }, [stat('Requests today', usage.requests || 0), stat('Errors today', usage.errors || 0), stat('Prompt tokens', (usage.promptTokens || 0).toLocaleString()), stat('Reply tokens', (usage.completionTokens || 0).toLocaleString()), stat('All-time requests', usage.total || 0)]));
    const lim = Object.entries(usage.limits || {}); const bm = Object.entries(usage.byModel || {});
    b.appendChild(card([el('table', {}, [el('tr', {}, [el('th', { textContent: 'Groq limit header' }), el('th', { textContent: 'Latest value' })]), ...(lim.length ? lim : [['(none yet, send a message)', '']]).map(([k, v]) => el('tr', {}, [el('td', { textContent: k }), el('td', { textContent: v })]))])]));
    b.appendChild(card([el('table', {}, [el('tr', {}, [el('th', { textContent: 'Model' }), el('th', { textContent: 'Requests today' })]), ...(bm.length ? bm : [['(none yet)', '']]).map(([k, v]) => el('tr', {}, [el('td', { textContent: k }), el('td', { textContent: v })]))])]));
    b.appendChild(btn('Reset counters', async () => { await omen.store.clear('usage'); render(); }, 'dng'));
  }
  if (tab === 'logs') {
    head('Logs', 'Errors and events, newest last').forEach((x) => b.appendChild(x));
    const logs = (await omen.store.get('logs')) || [];
    b.appendChild(el('pre', { textContent: logs.length ? logs.map((l) => l.t.replace('T', ' ').slice(0, 19) + '  [' + l.level + ']  ' + l.msg).join('\n') : 'No log entries.' }));
    b.appendChild(el('div', { style: 'margin-top:12px;display:flex;gap:10px' }, [btn('Copy', () => navigator.clipboard.writeText(logs.map((l) => l.t + ' ' + l.msg).join('\n'))), btn('Clear logs', async () => { await omen.store.clear('logs'); render(); }, 'dng')]));
  }
  if (tab === 'data') {
    head('Data and backup', 'Everything OMEN stores lives in one folder on this computer').forEach((x) => b.appendChild(x));
    const shown = { ...S, key: mask(S.key), adminPin: S.adminPin ? '(set)' : '' };
    b.appendChild(card([row('Open the data folder', info.userData || '', btn('Open', () => omen.openDataFolder())),
      row('Export settings', 'Saves a JSON file (without your key unless you choose)', el('div', {}, [btn('Export', () => omen.exportSettings(false)), ' ', btn('Export with key', () => omen.exportSettings(true))])),
      row('Import settings', 'Loads a JSON file made by Export', btn('Import', async () => { await omen.importSettings(); S = await omen.getSettings(); render(); })),
      row('Clear chat', '', btn('Clear', async () => { await omen.store.clear('chat'); })), row('Clear history (Recents)', '', btn('Clear', async () => { await omen.store.clear('history'); })),
      row('Clear notes', '', btn('Clear', async () => { await omen.store.clear('notes'); })),
      row('Reset all settings', 'Keeps only your Groq key', btn('Reset', async () => { S = await omen.resetSettings(); render(); }, 'dng'))]));
    b.appendChild(el('h3', { textContent: 'All current settings', style: 'font-weight:500' })); b.appendChild(el('pre', { textContent: JSON.stringify(shown, null, 2) }));
  }
  if (tab === 'security') {
    head('Security', 'Protect this panel with a PIN').forEach((x) => b.appendChild(x));
    const pin = el('input', { type: 'password', placeholder: 'New PIN' });
    b.appendChild(card([row('Admin PIN', S.adminPin ? 'A PIN is set. Opening this panel asks for it.' : 'No PIN. Anyone using this computer can open the panel.', el('div', {}, [pin, ' ', btn('Save PIN', async () => { await omen.setPin(pin.value); S = await omen.getSettings(); render(); }), ' ', btn('Remove PIN', async () => { await omen.setPin(''); S = await omen.getSettings(); render(); }, 'dng')]))]));
    b.appendChild(el('div', { className: 'sub', textContent: 'Your Groq key is stored in a file in the data folder. Anyone with access to your Windows account can read it. Keep your account locked.' }));
  }
  if (tab === 'about') {
    head('About', 'OMEN desktop').forEach((x) => b.appendChild(x));
    b.appendChild(el('div', { className: 'grid' }, [stat('Version', info.version), stat('Electron', info.electron), stat('Chrome', info.chrome), stat('Node', info.node), stat('Platform', info.platform), stat('System', info.os), stat('Uptime', info.uptime + ' s')]));
    b.appendChild(card([row('Data folder', info.userData, btn('Open', () => omen.openDataFolder()))]));
  }
}

async function start() {
  S = await omen.getSettings(); info = await omen.appInfo(); render();
  omen.on('settings:changed', (s) => { S = s; });
  clearInterval(timer); timer = setInterval(() => { if (tab === 'overview' || tab === 'usage' || tab === 'logs') render(); }, 4000);
  speechSynthesis.onvoiceschanged = () => { if (tab === 'voice') render(); };
}
async function init() {
  const s = await omen.getSettings();
  if (!s.adminPin) return start();
  $('#lock').style.display = 'flex';
  const go = async () => { if (await omen.verifyPin($('#pin').value)) { $('#lock').style.display = 'none'; start(); } else { $('#pinmsg').textContent = 'Wrong PIN'; $('#pin').value = ''; } };
  $('#unlock').onclick = go; $('#pin').addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
}
init();
