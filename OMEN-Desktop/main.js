'use strict';
const { app, BrowserWindow, Tray, Menu, globalShortcut, ipcMain, dialog, shell, nativeImage, Notification, session } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');
const { exec } = require('child_process');
const { pathToFileURL } = require('url');

const DEFAULTS = {
  key: '', name: '', model: '', visionModel: '',
  wind: true, glass: 28, voice: true, pitch: 0.45, rate: 0.85, voiceName: '',
  hotkey: 'CommandOrControl+Shift+Space', startup: false, tray: true, adminPin: '',
};
const STORES = ['chat', 'history', 'notes', 'logs', 'usage'];

let win = null, adminWin = null, tray = null, quitting = false;
const dataDir = () => app.getPath('userData');
const file = (n) => path.join(dataDir(), n);
function readJson(n, d) { try { return JSON.parse(fs.readFileSync(file(n), 'utf8')); } catch (e) { return d; } }
function writeJson(n, v) { fs.mkdirSync(dataDir(), { recursive: true }); fs.writeFileSync(file(n), JSON.stringify(v)); }
const settings = () => ({ ...DEFAULTS, ...readJson('settings.json', {}) });
const sha = (s) => crypto.createHash('sha256').update('omen:' + s).digest('hex');

function broadcast(ch, ...a) { for (const w of BrowserWindow.getAllWindows()) { try { w.webContents.send(ch, ...a); } catch (e) {} } }
function addLog(level, msg) {
  const logs = readJson('logs.json', []);
  logs.push({ t: new Date().toISOString(), level, msg: String(msg).slice(0, 600) });
  writeJson('logs.json', logs.slice(-300));
}

function createWindow() {
  win = new BrowserWindow({
    width: 1180, height: 780, minWidth: 760, minHeight: 560, backgroundColor: '#020205', title: 'OMEN',
    icon: path.join(__dirname, 'build/icon.png'), autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  });
  win.loadFile(path.join(__dirname, 'renderer/index.html'));
  win.on('close', (e) => { if (!quitting && settings().tray) { e.preventDefault(); win.hide(); } });
}

function openAdmin() {
  if (adminWin && !adminWin.isDestroyed()) { adminWin.show(); adminWin.focus(); return; }
  adminWin = new BrowserWindow({
    width: 1040, height: 780, minWidth: 820, minHeight: 560, backgroundColor: '#0e0e11', title: 'OMEN Admin panel',
    icon: path.join(__dirname, 'build/icon.png'), autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  });
  adminWin.loadFile(path.join(__dirname, 'renderer/admin.html'));
  adminWin.on('closed', () => { adminWin = null; });
}

function toggleWindow(talk) {
  if (!win) return;
  if (win.isVisible() && win.isFocused() && !talk) { win.hide(); return; }
  win.show(); win.focus();
  if (talk) win.webContents.send('hotkey:talk');
}

function registerHotkey() {
  globalShortcut.unregisterAll();
  const hk = settings().hotkey;
  if (!hk) return;
  try { globalShortcut.register(hk, () => toggleWindow(true)); } catch (e) { addLog('error', 'Hotkey failed: ' + e.message); }
}

function applyStartup() { try { app.setLoginItemSettings({ openAtLogin: !!settings().startup }); } catch (e) {} }

function makeTray() {
  try {
    const img = nativeImage.createFromPath(path.join(__dirname, 'build/icon.png')).resize({ width: 18, height: 18 });
    tray = new Tray(img);
    tray.setToolTip('OMEN');
    tray.on('click', () => toggleWindow(false));
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: 'Show / hide OMEN', click: () => toggleWindow(false) },
      { label: 'Talk to OMEN', click: () => toggleWindow(true) },
      { label: 'Admin panel', click: () => openAdmin() },
      { type: 'separator' },
      { label: 'Quit', click: () => { quitting = true; app.quit(); } },
    ]));
  } catch (e) { addLog('error', 'Tray failed: ' + e.message); }
}

if (!app.requestSingleInstanceLock()) { app.quit(); } else {
  app.on('second-instance', () => { if (win) { win.show(); win.focus(); } });
  app.whenReady().then(() => {
    session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(['media', 'audioCapture', 'notifications'].includes(perm)));
    createWindow(); makeTray(); registerHotkey(); applyStartup();
  });
  app.on('before-quit', () => { quitting = true; });
  app.on('will-quit', () => globalShortcut.unregisterAll());
  app.on('window-all-closed', () => { if (!settings().tray) app.quit(); });
}

// ---------------- IPC ----------------
ipcMain.handle('settings:get', () => settings());
ipcMain.handle('settings:set', (e, patch) => {
  const before = settings();
  const next = { ...before, ...patch };
  writeJson('settings.json', next);
  if (patch.hotkey !== undefined && patch.hotkey !== before.hotkey) registerHotkey();
  if (patch.startup !== undefined) applyStartup();
  broadcast('settings:changed', next);
  return next;
});
ipcMain.handle('settings:reset', () => { const keep = { key: settings().key }; writeJson('settings.json', keep); registerHotkey(); applyStartup(); broadcast('settings:changed', settings()); return settings(); });

ipcMain.handle('store:get', (e, n) => (STORES.includes(n) ? readJson(n + '.json', n === 'usage' ? {} : []) : null));
ipcMain.handle('store:set', (e, n, v) => { if (STORES.includes(n)) writeJson(n + '.json', v); return true; });
ipcMain.handle('store:clear', (e, n) => { if (STORES.includes(n)) writeJson(n + '.json', n === 'usage' ? {} : []); return true; });
ipcMain.handle('log', (e, level, msg) => { addLog(level, msg); return true; });

// network calls run here (no browser CORS limits) and report Groq's rate-limit headers
ipcMain.handle('http', async (e, o) => {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), o.timeout || 60000);
  try {
    const r = await fetch(o.url, { method: o.method || 'GET', headers: o.headers || {}, body: o.body, signal: ctl.signal, redirect: 'follow' });
    const text = await r.text();
    const headers = {};
    r.headers.forEach((v, k) => { if (/^x-ratelimit|^retry-after|^content-type/.test(k)) headers[k] = v; });
    return { ok: r.ok, status: r.status, text: text.slice(0, 1500000), headers };
  } catch (err) { return { ok: false, status: 0, text: '', headers: {}, error: String(err.message || err) }; }
  finally { clearTimeout(timer); }
});

ipcMain.handle('http:image', async (e, o) => {
  try {
    const r = await fetch(o.url, { headers: o.headers || { 'User-Agent': 'OMEN/1.0 (desktop assistant)' }, redirect: 'follow' });
    const mime = r.headers.get('content-type') || '';
    if (!r.ok || !mime.startsWith('image')) return { ok: false, error: 'HTTP ' + r.status + ' ' + mime };
    const buf = Buffer.from(await r.arrayBuffer());
    if (buf.length < 3000) return { ok: false, error: 'image too small' };
    const dir = path.join(dataDir(), 'img'); fs.mkdirSync(dir, { recursive: true });
    const p = path.join(dir, 'i' + Date.now() + '_' + Math.floor(Math.random() * 1e5) + '.jpg');
    fs.writeFileSync(p, buf);
    return { ok: true, path: p, url: pathToFileURL(p).href };
  } catch (err) { return { ok: false, error: String(err.message || err) }; }
});

ipcMain.handle('file:saveImage', (e, o) => {
  const dir = path.join(dataDir(), 'img'); fs.mkdirSync(dir, { recursive: true });
  const p = path.join(dir, 'p' + Date.now() + '.jpg');
  fs.writeFileSync(p, Buffer.from(o.b64, 'base64'));
  return { path: p, url: pathToFileURL(p).href, b64: o.b64 };
});

ipcMain.handle('http:transcribe', async (e, o) => {
  try {
    const fd = new FormData();
    fd.append('file', new Blob([Buffer.from(o.b64, 'base64')], { type: o.mime || 'audio/webm' }), 'speech.webm');
    fd.append('model', 'whisper-large-v3-turbo'); fd.append('response_format', 'json'); fd.append('language', 'en');
    const r = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', { method: 'POST', headers: { Authorization: 'Bearer ' + o.key }, body: fd });
    const j = await r.json();
    if (!r.ok) return { ok: false, error: (j.error && j.error.message) || 'HTTP ' + r.status };
    return { ok: true, text: (j.text || '').trim() };
  } catch (err) { return { ok: false, error: String(err.message || err) }; }
});

const IMG = /\.(png|jpe?g|webp|gif|bmp)$/i;
async function readAny(p) {
  const name = path.basename(p); const lower = name.toLowerCase();
  if (IMG.test(lower)) {
    const buf = fs.readFileSync(p);
    const dir = path.join(dataDir(), 'img'); fs.mkdirSync(dir, { recursive: true });
    const copy = path.join(dir, 'p' + Date.now() + '_' + Math.floor(Math.random() * 1e5) + path.extname(lower));
    fs.writeFileSync(copy, buf);
    return { kind: 'image', name, path: copy, url: pathToFileURL(copy).href, b64: buf.toString('base64'), mime: 'image/' + (lower.endsWith('png') ? 'png' : 'jpeg') };
  }
  let text = '';
  if (lower.endsWith('.pdf')) {
    const pdf = require('pdf-parse/lib/pdf-parse.js');
    text = (await pdf(fs.readFileSync(p))).text;
    if (!text.trim()) throw new Error(name + ' looks like a scanned PDF (no text). Add it as photos instead.');
  } else if (lower.endsWith('.docx')) {
    text = (await require('mammoth').extractRawText({ path: p })).value;
  } else if (lower.endsWith('.pptx')) {
    const AdmZip = require('adm-zip'); const z = new AdmZip(p);
    text = z.getEntries().filter((x) => /^ppt\/slides\/slide\d+\.xml$/.test(x.entryName))
      .map((x) => x.getData().toString('utf8').replace(/<\/a:p>/g, '\n').replace(/<[^>]+>/g, '')).join('\n');
  } else if (/\.(txt|md|csv|json|log|xml|html?|js|py|java|kt|c|cpp|ini|yml|yaml)$/i.test(lower)) {
    text = fs.readFileSync(p, 'utf8');
  } else throw new Error('Unsupported file type: ' + name);
  text = text.replace(/&amp;/g, '&').replace(/\n\s*\n+/g, '\n').trim();
  if (!text) throw new Error('No readable text in ' + name);
  return { kind: 'doc', name, text: text.slice(0, 30000) };
}

ipcMain.handle('file:read', async (e, paths) => {
  const out = [], errors = [];
  for (const p of (paths || []).slice(0, 8)) { try { out.push(await readAny(p)); } catch (err) { errors.push(String(err.message || err)); } }
  return { files: out, errors };
});
ipcMain.handle('file:pick', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openFile', 'multiSelections'],
    filters: [{ name: 'Photos and documents', extensions: ['png', 'jpg', 'jpeg', 'webp', 'gif', 'pdf', 'docx', 'pptx', 'txt', 'md', 'csv', 'json'] }] });
  return r.canceled ? [] : r.filePaths;
});

ipcMain.handle('app:info', () => ({
  version: app.getVersion(), electron: process.versions.electron, node: process.versions.node, chrome: process.versions.chrome,
  platform: process.platform + ' ' + process.arch, os: os.type() + ' ' + os.release(), userData: dataDir(), uptime: Math.round(process.uptime()),
}));
ipcMain.handle('sys:stats', () => ({
  freeMem: os.freemem(), totalMem: os.totalmem(), load: os.loadavg(), cpus: os.cpus().length,
  rss: process.memoryUsage().rss, heap: process.memoryUsage().heapUsed,
}));

ipcMain.handle('shell:openExternal', (e, u) => { if (/^https?:\/\//i.test(u)) { shell.openExternal(u); return true; } return false; });
ipcMain.handle('sys:open', (e, name) => new Promise((resolve) => {
  const n = String(name || '').trim().toLowerCase();
  if (!/^[\w .\-]{1,40}$/.test(n)) return resolve({ ok: false, error: 'That name is not allowed' });
  const win32 = { calculator: 'calc', calc: 'calc', notepad: 'notepad', paint: 'mspaint', 'file explorer': 'explorer', explorer: 'explorer', settings: 'ms-settings:', 'task manager': 'taskmgr', camera: 'microsoft.windows.camera:', word: 'winword', excel: 'excel', powerpoint: 'powerpnt', chrome: 'chrome', edge: 'msedge', firefox: 'firefox', spotify: 'spotify', vscode: 'code', 'visual studio code': 'code', whatsapp: 'whatsapp:', discord: 'discord' };
  let cmd;
  if (process.platform === 'win32') cmd = 'start "" "' + (win32[n] || n) + '"';
  else if (process.platform === 'darwin') cmd = 'open -a "' + n + '"';
  else cmd = n.replace(/ /g, '-');
  exec(cmd, { windowsHide: true }, (err) => resolve(err ? { ok: false, error: 'I could not open ' + n } : { ok: true }));
}));
ipcMain.handle('notify', (e, title, body) => { try { new Notification({ title, body }).show(); } catch (err) {} return true; });
ipcMain.handle('admin:open', () => { openAdmin(); return true; });
ipcMain.handle('data:openFolder', () => shell.openPath(dataDir()));
ipcMain.handle('admin:verify', (e, pin) => { const s = settings(); return !s.adminPin || s.adminPin === sha(String(pin || '')); });

ipcMain.handle('data:export', async (e, withKey) => {
  const r = await dialog.showSaveDialog({ defaultPath: 'omen-settings.json' });
  if (r.canceled) return false;
  const s = settings(); if (!withKey) s.key = ''; s.adminPin = '';
  fs.writeFileSync(r.filePath, JSON.stringify(s, null, 2)); return true;
});
ipcMain.handle('data:import', async () => {
  const r = await dialog.showOpenDialog({ properties: ['openFile'], filters: [{ name: 'JSON', extensions: ['json'] }] });
  if (r.canceled) return false;
  const j = JSON.parse(fs.readFileSync(r.filePaths[0], 'utf8')); delete j.adminPin;
  if (!j.key) delete j.key;
  writeJson('settings.json', { ...settings(), ...j }); registerHotkey(); applyStartup(); broadcast('settings:changed', settings()); return true;
});
ipcMain.handle('admin:setPin', (e, pin) => { writeJson('settings.json', { ...settings(), adminPin: pin ? sha(String(pin)) : '' }); return true; });
