// Editor (Monaco), modelos de arquivo, abas e salvamento.
import { fs, clipboard } from './bridge.js';

let monaco = null;
let editor = null;
let settings = {};
let virtualLoader = null; // (uri) => Promise<string>  (conteudo de jdt://, definido pelo lsp.js)

const entries = new Map(); // uri -> {key, path, uri, model, readOnly, dirty, timer, hasTab, viewState}
const tabs = [];           // entries com aba, na ordem
let activeKey = null;
const listeners = { tabs: [], saved: [], active: [] };

const LANGS = {
  java: 'java', xml: 'xml', pom: 'xml', md: 'markdown', yml: 'yaml', yaml: 'yaml', json: 'plaintext',
  gradle: 'plaintext', properties: 'ini', sh: 'shell', txt: 'plaintext', html: 'html', css: 'css',
  js: 'javascript', kt: 'kotlin', kts: 'kotlin', sql: 'sql', gitignore: 'plaintext',
};

export const languageFor = (path) => {
  const name = path.split('/').pop();
  const ext = name.includes('.') ? name.split('.').pop().toLowerCase() : '';
  return LANGS[ext] || 'plaintext';
};

const fire = (kind, ...args) => listeners[kind].forEach((fn) => { try { fn(...args); } catch (e) { console.error(e); } });
export const onTabsChanged = (fn) => listeners.tabs.push(fn);
export const onSaved = (fn) => listeners.saved.push(fn);
export const onActiveChanged = (fn) => listeners.active.push(fn);

export function setVirtualLoader(fn) { virtualLoader = fn; }
export const getMonaco = () => monaco;
export const getEditor = () => editor;

export function initEditor(mon, container, s) {
  monaco = mon;
  settings = s;
  editor = monaco.editor.create(container, {
    model: null,
    theme: s.theme === 'light' ? 'vs' : 'vs-dark',
    fontSize: s.fontSize,
    tabSize: s.tabSize,
    insertSpaces: true,
    automaticLayout: true,
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    wordWrap: s.wordWrap ? 'on' : 'off',
    fixedOverflowWidgets: true,
    lineNumbersMinChars: 3,
    glyphMargin: false,
    folding: true,
    renderLineHighlight: 'line',
    quickSuggestions: { other: true, comments: false, strings: false },
    suggestOnTriggerCharacters: true,
    acceptSuggestionOnEnter: 'on',
    parameterHints: { enabled: true },
    bracketPairColorization: { enabled: true },
    guides: { indentation: true },
    padding: { top: 6, bottom: 60 },
    accessibilitySupport: 'off',
    dragAndDrop: false,
    mouseWheelZoom: false,
    stickyScroll: { enabled: false },
    contextmenu: true,
  });
  window.addEventListener('blur', () => saveAll());
  document.addEventListener('visibilitychange', () => { if (document.hidden) saveAll(); });
  return editor;
}

export function applySettings(s) {
  settings = s;
  monaco.editor.setTheme(s.theme === 'light' ? 'vs' : 'vs-dark');
  editor.updateOptions({ fontSize: s.fontSize, tabSize: s.tabSize, wordWrap: s.wordWrap ? 'on' : 'off' });
  entries.forEach((e) => e.model.updateOptions({ tabSize: s.tabSize, insertSpaces: true }));
}

// ----------------------------------------------------------------- modelos

function track(entry) {
  entry.model.onDidChangeContent(() => {
    if (entry.readOnly || entry.loading) return;
    setDirty(entry, true);
    // Modelos sem aba (editados por refatoracao/renomear) sempre salvam sozinhos.
    if (settings.autosave || !entry.hasTab) {
      clearTimeout(entry.timer);
      entry.timer = setTimeout(() => saveEntry(entry), 1200);
    }
  });
}

/** Modelo do arquivo (cria se preciso). Nao abre aba. */
export async function getModel(path) {
  const uri = monaco.Uri.file(path).toString();
  let e = entries.get(uri);
  if (e) return e.model;
  const text = await fs.read(path);
  e = entries.get(uri); // pode ter sido criado enquanto lia
  if (e) return e.model;
  const model = monaco.editor.createModel(text, languageFor(path), monaco.Uri.file(path));
  model.updateOptions({ tabSize: settings.tabSize, insertSpaces: true });
  e = { key: uri, uri, path, model, readOnly: false, dirty: false, timer: null, hasTab: false };
  entries.set(uri, e);
  track(e);
  return model;
}

/** Garante modelo para qualquer URI (file:// ou jdt://). */
export async function getModelForUri(uriStr) {
  const uri = monaco.Uri.parse(uriStr);
  if (uri.scheme === 'file') return getModel(uri.path);
  const key = uri.toString();
  let e = entries.get(key);
  if (e) return e.model;
  if (!virtualLoader) throw new Error('sem carregador para ' + uriStr);
  const text = await virtualLoader(uriStr);
  e = entries.get(key);
  if (e) return e.model;
  const model = monaco.editor.createModel(text, 'java', uri);
  e = { key, uri: key, path: uri.path, model, readOnly: true, dirty: false, timer: null, hasTab: false, title: uri.path.split('/').pop() };
  entries.set(key, e);
  return model;
}

export function modelsOfLanguage(lang) {
  return [...entries.values()].filter((e) => e.model.getLanguageId() === lang && !e.readOnly).map((e) => e.model);
}

// ----------------------------------------------------------------- abas

export async function openFile(path, pos) {
  await getModel(path);
  return openEntry(entries.get(monaco.Uri.file(path).toString()), pos);
}

export async function openUri(uriStr, pos) {
  await getModelForUri(uriStr);
  return openEntry(entries.get(monaco.Uri.parse(uriStr).toString()), pos);
}

function openEntry(entry, pos) {
  if (!entry.hasTab) {
    entry.hasTab = true;
    tabs.push(entry);
  }
  activate(entry.key);
  if (pos) revealAt(pos);
  return entry;
}

export function revealAt(pos) {
  if (!editor) return;
  const line = pos.line || pos.lineNumber || 1;
  const col = pos.column || pos.col || 1;
  editor.setPosition({ lineNumber: line, column: col });
  editor.revealPositionInCenter({ lineNumber: line, column: col });
  editor.focus();
}

function activate(key) {
  const prev = entries.get(activeKey);
  if (prev && prev.hasTab) prev.viewState = editor.saveViewState();
  activeKey = key;
  const e = entries.get(key);
  editor.setModel(e.model);
  editor.updateOptions({ readOnly: e.readOnly });
  if (e.viewState) editor.restoreViewState(e.viewState);
  renderTabs();
  fire('active', e.readOnly ? null : e.path);
}

export async function closeTab(key) {
  const e = entries.get(key);
  if (!e || !e.hasTab) return;
  if (e.dirty && !e.readOnly) await saveEntry(e);
  const i = tabs.indexOf(e);
  tabs.splice(i, 1);
  e.hasTab = false;
  e.viewState = null;
  if (activeKey === key) {
    const next = tabs[Math.min(i, tabs.length - 1)];
    if (next) activate(next.key);
    else { activeKey = null; editor.setModel(null); renderTabs(); fire('active', null); }
  } else renderTabs();
  if (e.readOnly) { e.model.dispose(); entries.delete(key); }
}

/** Fecha abas (e descarta modelos) de arquivos dentro de `prefix` (apos renomear/apagar). */
export async function closeUnder(prefix, { save = true } = {}) {
  for (const e of [...entries.values()]) {
    if (e.path === prefix || e.path.startsWith(prefix + '/')) {
      if (save && e.dirty && !e.readOnly) await saveEntry(e).catch(() => {});
      if (e.hasTab) await closeTab(e.key);
      clearTimeout(e.timer);
      e.model.dispose();
      entries.delete(e.key);
    }
  }
  renderTabs();
}

export async function closeAll() {
  await saveAll();
  for (const e of [...entries.values()]) {
    clearTimeout(e.timer);
    e.model.dispose();
  }
  entries.clear();
  tabs.length = 0;
  activeKey = null;
  editor.setModel(null);
  renderTabs();
  fire('active', null);
}

export function activePath() {
  const e = entries.get(activeKey);
  return e && !e.readOnly ? e.path : null;
}

export const openPaths = () => tabs.filter((t) => !t.readOnly).map((t) => t.path);

// ----------------------------------------------------------------- salvar

function setDirty(entry, v) {
  if (entry.dirty === v) return;
  entry.dirty = v;
  if (entry.hasTab) renderTabs();
}

async function saveEntry(entry) {
  if (entry.readOnly) return;
  clearTimeout(entry.timer);
  try {
    await fs.write(entry.path, entry.model.getValue());
    setDirty(entry, false);
    fire('saved', entry.path);
  } catch (e) {
    console.error('falha ao salvar', entry.path, e);
    window.dispatchEvent(new CustomEvent('ide-error', { detail: 'Falha ao salvar ' + entry.path + ': ' + e.message }));
  }
}

export async function saveActive() {
  const e = entries.get(activeKey);
  if (e) await saveEntry(e);
}

export async function saveAll() {
  await Promise.all([...entries.values()].filter((e) => e.dirty && !e.readOnly).map(saveEntry));
}

// ----------------------------------------------------------------- UI das abas

function renderTabs() {
  const bar = document.getElementById('tabs');
  const welcome = document.getElementById('welcome');
  const host = document.getElementById('editor');
  bar.textContent = '';
  for (const t of tabs) {
    const el = document.createElement('div');
    el.className = 'tab' + (t.key === activeKey ? ' active' : '') + (t.readOnly ? ' readonly' : '');
    const name = document.createElement('span');
    name.className = 'tab-name';
    name.textContent = (t.title || t.path.split('/').pop()) + (t.dirty ? ' •' : '');
    name.title = t.path;
    const x = document.createElement('button');
    x.className = 'tab-x';
    x.textContent = '×';
    x.setAttribute('aria-label', 'Fechar');
    x.addEventListener('click', (ev) => { ev.stopPropagation(); closeTab(t.key); });
    el.addEventListener('click', () => { if (t.key !== activeKey) activate(t.key); else editor.focus(); });
    el.append(name, x);
    bar.append(el);
  }
  const has = tabs.length > 0;
  welcome.style.display = has ? 'none' : '';
  host.style.visibility = has ? 'visible' : 'hidden';
  document.getElementById('tabrow').style.display = has ? 'flex' : 'none';
  const active = bar.querySelector('.tab.active');
  if (active) active.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  fire('tabs', tabs.length);
}

// ----------------------------------------------------------------- acoes (toque)

export function insertText(text) {
  if (!editor) return;
  editor.focus();
  editor.trigger('keyboard', 'type', { text });
}

export function command(id) {
  if (!editor) return;
  editor.focus();
  editor.trigger('keyboard', id, null);
}

export function action(id) {
  if (!editor) return;
  editor.focus();
  const a = editor.getAction(id);
  if (a) a.run();
}

function selectedText() {
  const sel = editor.getSelection();
  const model = editor.getModel();
  if (!sel || !model) return { text: '', range: null };
  if (sel.isEmpty()) {
    const ln = sel.startLineNumber;
    return { text: model.getLineContent(ln) + '\n', range: new monaco.Range(ln, 1, ln + 1, 1), line: true };
  }
  return { text: model.getValueInRange(sel), range: sel };
}

export async function copy() {
  if (!editor || !editor.getModel()) return;
  const { text } = selectedText();
  if (text) await clipboard.write(text);
}

export async function cut() {
  if (!editor || !editor.getModel() || editor.getOption(monaco.editor.EditorOption.readOnly)) return;
  const { text, range } = selectedText();
  if (!text) return;
  await clipboard.write(text);
  editor.executeEdits('cut', [{ range, text: '', forceMoveMarkers: true }]);
  editor.focus();
}

export async function paste() {
  if (!editor || !editor.getModel() || editor.getOption(monaco.editor.EditorOption.readOnly)) return;
  const text = await clipboard.read();
  if (!text) return;
  editor.focus();
  editor.executeEdits('paste', editor.getSelections().map((range) => ({ range, text, forceMoveMarkers: true })));
}
