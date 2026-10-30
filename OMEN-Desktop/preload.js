'use strict';
const { contextBridge, ipcRenderer } = require('electron');
const call = (ch, ...a) => ipcRenderer.invoke(ch, ...a);

contextBridge.exposeInMainWorld('omen', {
  getSettings: () => call('settings:get'),
  setSettings: (patch) => call('settings:set', patch),
  resetSettings: () => call('settings:reset'),
  store: { get: (n) => call('store:get', n), set: (n, v) => call('store:set', n, v), clear: (n) => call('store:clear', n) },
  http: (o) => call('http', o),
  image: (o) => call('http:image', o),
  saveImage: (o) => call('file:saveImage', o),
  transcribe: (o) => call('http:transcribe', o),
  pickFiles: () => call('file:pick'),
  readFiles: (paths) => call('file:read', paths),
  appInfo: () => call('app:info'),
  sysStats: () => call('sys:stats'),
  openExternal: (u) => call('shell:openExternal', u),
  openApp: (n) => call('sys:open', n),
  notify: (t, b) => call('notify', t, b),
  openAdmin: () => call('admin:open'),
  openDataFolder: () => call('data:openFolder'),
  exportSettings: (withKey) => call('data:export', withKey),
  importSettings: () => call('data:import'),
  verifyPin: (p) => call('admin:verify', p),
  setPin: (p) => call('admin:setPin', p),
  log: (level, msg) => call('log', level, msg),
  on: (ch, fn) => ipcRenderer.on(ch, (_e, ...a) => fn(...a)),
});
