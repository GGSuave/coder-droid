// Ponte com o app Android (WebMessageListener "ideBridge"). Sem rede: tudo passa pela WebView.
const native = window.ideBridge;
const pending = new Map();
const handlers = new Map();
let seq = 0;

if (native) {
  native.onmessage = (e) => {
    let m;
    try { m = JSON.parse(e.data); } catch { return; }
    if (m.id !== undefined) {
      const p = pending.get(m.id);
      if (!p) return;
      pending.delete(m.id);
      if (m.error) p.rej(new Error(m.error)); else p.res(m.result);
    } else if (m.event) {
      for (const fn of handlers.get(m.event) || []) {
        try { fn(m.data); } catch (err) { console.error(err); }
      }
    }
  };
}

export const hasNative = !!native;

export function call(method, params = {}) {
  return new Promise((res, rej) => {
    if (!native) return rej(new Error('ponte indisponivel (fora do app)'));
    const id = ++seq;
    pending.set(id, { res, rej });
    native.postMessage(JSON.stringify({ id, method, params }));
  });
}

export function on(event, fn) {
  if (!handlers.has(event)) handlers.set(event, []);
  handlers.get(event).push(fn);
}

export const fs = {
  list: (path) => call('fs.list', { path }),
  read: (path) => call('fs.read', { path }),
  write: (path, text) => call('fs.write', { path, text }),
  mkdirs: (path) => call('fs.mkdirs', { path }),
  exists: (path) => call('fs.exists', { path }),
  rename: (from, to) => call('fs.rename', { from, to }),
  delete: (path) => call('fs.delete', { path }),
};

// ----------------------------------------------------------------- trabalhos (processos)
// A saida pode chegar antes da resposta com o id do trabalho: guardamos e entregamos depois.
const subs = new Map();
const early = new Map();

on('proc.out', (d) => {
  const s = subs.get(d.id);
  if (s) s.onOut(d.text);
  else { if (!early.has(d.id)) early.set(d.id, []); early.get(d.id).push({ t: 'out', v: d.text }); }
});
on('proc.exit', (d) => {
  const s = subs.get(d.id);
  if (s) { subs.delete(d.id); s.onExit(d.code); }
  else { if (!early.has(d.id)) early.set(d.id, []); early.get(d.id).push({ t: 'exit', v: d.code }); }
});

export async function startJob(cmd, cwd, onOut, onExit) {
  const id = await call('proc.run', { cmd, cwd });
  subs.set(id, { onOut, onExit });
  const buffered = early.get(id);
  if (buffered) {
    early.delete(id);
    for (const e of buffered) {
      if (e.t === 'out') onOut(e.v);
      else { subs.delete(id); onExit(e.v); }
    }
  }
  return id;
}
export const stopJob = (id) => call('proc.kill', { id });
export const jobInput = (id, text) => call('proc.stdin', { id, text });

/** Roda um comando e devolve {code, out} quando terminar. */
export function runCapture(cmd, cwd = '/root') {
  return new Promise((resolve) => {
    let out = '';
    startJob(cmd, cwd, (t) => { out += t; }, (code) => resolve({ code, out }))
      .catch((e) => resolve({ code: -1, out: String(e.message || e) }));
  });
}

// ----------------------------------------------------------------- area de transferencia
// A WebView nao deixa a pagina pedir permissao para navigator.clipboard.readText().
export function installClipboard() {
  if (!native) return;
  const clip = {
    readText: () => call('clip.read').then((t) => t || ''),
    writeText: (t) => call('clip.write', { text: String(t) }).then(() => {}),
    read: () => call('clip.read').then((t) => [new ClipboardItem({ 'text/plain': new Blob([t || ''], { type: 'text/plain' }) })]),
    write: async (items) => {
      for (const item of items) {
        if (item.types.includes('text/plain')) await call('clip.write', { text: await (await item.getType('text/plain')).text() });
      }
    },
    addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false,
  };
  try { Object.defineProperty(Navigator.prototype, 'clipboard', { get: () => clip, configurable: true }); } catch { /* ignora */ }
}

export const clipboard = {
  read: () => call('clip.read').then((t) => t || ''),
  write: (text) => call('clip.write', { text }),
};
