// Interface: dialogos, arvore de arquivos, paineis, execucao (Maven/Gradle/java), projetos, configuracoes.
import { fs, call, startJob, stopJob, jobInput, runCapture } from './bridge.js';
import * as ed from './editor.js';
import * as lsp from './lsp.js';
import { newProjectFiles } from './templates.js';

const $ = (s, r = document) => r.querySelector(s);
export function el(tag, props = {}, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === 'class') n.className = v;
    else if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
    else if (v !== undefined && v !== null) n.setAttribute(k, v);
  }
  for (const k of kids.flat()) if (k !== null && k !== undefined) n.append(k.nodeType ? k : document.createTextNode(String(k)));
  return n;
}
const q = (s) => "'" + String(s).replace(/'/g, "'\\''") + "'";

export const PROJECTS = '/root/projects';
export const state = { root: null, kind: 'none', job: null, settings: null };

// ----------------------------------------------------------------- configuracoes
const DEFAULTS = { theme: 'dark', fontSize: 14, tabSize: 4, autosave: true, wordWrap: false };
export function loadSettings() {
  try { return { ...DEFAULTS, ...JSON.parse(localStorage.getItem('cdj.settings') || '{}') }; } catch { return { ...DEFAULTS }; }
}
function applyTheme(s) { document.body.classList.toggle('light', s.theme === 'light'); }

// ----------------------------------------------------------------- toast e dialogos
let toastTimer = null;
export function toast(msg, ms = 3500) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), ms);
}
window.addEventListener('ide-error', (e) => toast(e.detail, 6000));

function dialog({ title, content, buttons }) {
  return new Promise((resolve) => {
    const close = (v) => { back.remove(); resolve(v); };
    const box = el('div', { class: 'modal' }, el('h3', {}, title), content || '');
    const bar = el('div', { class: 'modal-buttons' });
    for (const b of buttons) {
      bar.append(el('button', { class: 'btn' + (b.primary ? ' primary' : ''), onclick: () => close(b.value) }, b.label));
    }
    box.append(bar);
    const back = el('div', { class: 'modal-backdrop', onmousedown: (e) => { if (e.target === back) close(null); } }, box);
    $('#modal-root').append(back);
    const first = box.querySelector('input,select');
    if (first) setTimeout(() => { first.focus(); if (first.select) first.select(); }, 50);
  });
}

export async function promptText(title, value = '', placeholder = '') {
  const input = el('input', { type: 'text', value, placeholder, class: 'input', autocapitalize: 'off', autocomplete: 'off', spellcheck: 'false' });
  const p = dialog({ title, content: input, buttons: [{ label: 'Cancelar', value: null }, { label: 'OK', value: 'ok', primary: true }] });
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') input.closest('.modal').querySelector('.primary').click(); });
  const r = await p;
  return r === 'ok' ? input.value.trim() : null;
}

export async function confirmBox(message, okLabel = 'OK') {
  return (await dialog({ title: 'Confirmar', content: el('p', {}, message), buttons: [{ label: 'Cancelar', value: false }, { label: okLabel, value: true, primary: true }] })) === true;
}

export async function chooseBox(title, items, extra = []) {
  const list = el('div', { class: 'choose' });
  let resolveFn;
  const result = new Promise((r) => { resolveFn = r; });
  for (const it of items) {
    list.append(el('button', { class: 'choose-item', onclick: () => { list.closest('.modal-backdrop').remove(); resolveFn(it.value); } },
      el('span', {}, it.label), it.sub ? el('small', {}, it.sub) : null));
  }
  const d = dialog({ title, content: list, buttons: [{ label: 'Cancelar', value: null }, ...extra] });
  const r = await Promise.race([d, result]);
  return r;
}

// ----------------------------------------------------------------- arvore de arquivos
const tree = { expanded: new Set(), cache: new Map(), selected: null };
const HIDDEN = new Set(['.git', '.gradle', '.idea', '.settings', '.project', '.classpath', '.vscode', '.metadata']);

async function loadDir(path) {
  if (!tree.cache.has(path)) {
    const list = await fs.list(path);
    tree.cache.set(path, list.filter((e) => !HIDDEN.has(e.name) && !e.name.endsWith('.cdtmp')));
  }
  return tree.cache.get(path);
}

function icon(e) {
  if (e.dir) return '📁';
  const ext = e.name.split('.').pop().toLowerCase();
  return { java: '☕', xml: '🧾', gradle: '🐘', kts: '🐘', md: '📝', properties: '⚙', json: '🧾' }[ext] || '📄';
}

export async function renderTree() {
  const box = $('#tree');
  box.textContent = '';
  if (!state.root) { box.append(el('div', { class: 'tree-empty' }, 'Nenhum projeto aberto')); return; }
  await renderDir(state.root, box, 0);
}

async function renderDir(path, parent, depth) {
  let entries = [];
  try { entries = await loadDir(path); } catch (e) { toast('Erro ao listar: ' + e.message); }
  for (const e of entries) {
    const p = path + '/' + e.name;
    const open = e.dir && tree.expanded.has(p);
    const row = el('div', { class: 'row' + (tree.selected === p ? ' sel' : ''), style: `padding-left:${8 + depth * 14}px` },
      el('span', { class: 'caret' }, e.dir ? (open ? '▾' : '▸') : ''),
      el('span', { class: 'ico' }, icon(e)),
      el('span', { class: 'name' }, e.name));
    attachRow(row, p, e);
    parent.append(row);
    if (open) await renderDir(p, parent, depth + 1);
  }
}

function attachRow(row, path, e) {
  let timer = null;
  let longFired = false;
  const cancel = () => { clearTimeout(timer); };
  row.addEventListener('pointerdown', () => {
    longFired = false;
    timer = setTimeout(() => { longFired = true; rowMenu(path, e); }, 480);
  });
  row.addEventListener('pointerup', cancel);
  row.addEventListener('pointermove', cancel);
  row.addEventListener('pointercancel', cancel);
  row.addEventListener('contextmenu', (ev) => { ev.preventDefault(); cancel(); if (!longFired) rowMenu(path, e); });
  row.addEventListener('click', async () => {
    if (longFired) return;
    tree.selected = path;
    if (e.dir) {
      if (tree.expanded.has(path)) tree.expanded.delete(path); else tree.expanded.add(path);
      await renderTree();
    } else {
      await ed.openFile(path).catch((err) => toast('Não foi possível abrir: ' + err.message));
      await renderTree();
      if (window.innerWidth < 760) $('#sidebar').classList.add('hidden');
    }
  });
}

const dirOf = (p) => p.substring(0, p.lastIndexOf('/'));
function targetDir() {
  const s = tree.selected;
  if (!s) return state.root;
  const parentEntries = tree.cache.get(dirOf(s)) || [];
  const entry = parentEntries.find((x) => x.name === s.split('/').pop());
  return entry && !entry.dir ? dirOf(s) : s;
}

async function rowMenu(path, e) {
  tree.selected = path;
  const dir = e.dir ? path : dirOf(path);
  const act = await chooseBox(e.name, [
    { label: 'Novo arquivo aqui…', value: 'file' },
    { label: 'Nova pasta aqui…', value: 'dir' },
    { label: 'Renomear…', value: 'rename' },
    { label: 'Excluir…', value: 'delete' },
    { label: 'Copiar caminho', value: 'copy' },
  ]);
  if (act === 'file') await newFile(dir);
  else if (act === 'dir') await newFolder(dir);
  else if (act === 'rename') await renamePath(path);
  else if (act === 'delete') await deletePath(path, e.dir);
  else if (act === 'copy') { await call('clip.write', { text: path }); toast('Caminho copiado'); }
}

async function refreshDir(path) {
  tree.cache.delete(path);
  tree.expanded.add(path);
  await renderTree();
}

function javaSkeleton(path) {
  const m = /\/src\/(?:main|test)\/java\/(.+)\/([^/]+)\.java$/.exec(path);
  const cls = path.split('/').pop().replace(/\.java$/, '');
  const pkg = m ? m[1].replace(/\//g, '.') : null;
  return (pkg ? `package ${pkg};\n\n` : '') + `public class ${cls} {\n\n}\n`;
}

export async function newFile(dir = targetDir()) {
  const name = await promptText('Novo arquivo', '', 'Nome (ex.: Pessoa.java)');
  if (!name) return;
  const path = dir + '/' + name;
  try {
    if (await fs.exists(path)) return toast('Já existe: ' + name);
    await fs.write(path, name.endsWith('.java') ? javaSkeleton(path) : '');
    lsp.fileChanged(path, 1);
    await refreshDir(dir);
    await ed.openFile(path);
  } catch (e) { toast('Erro: ' + e.message); }
}

export async function newFolder(dir = targetDir()) {
  const name = await promptText('Nova pasta', '', 'Nome');
  if (!name) return;
  try { await fs.mkdirs(dir + '/' + name); await refreshDir(dir); } catch (e) { toast('Erro: ' + e.message); }
}

async function renamePath(path) {
  const old = path.split('/').pop();
  const name = await promptText('Renomear', old);
  if (!name || name === old) return;
  const to = dirOf(path) + '/' + name;
  try {
    await ed.closeUnder(path);
    await fs.rename(path, to);
    lsp.fileChanged(path, 3); lsp.fileChanged(to, 1);
    await refreshDir(dirOf(path));
  } catch (e) { toast('Erro: ' + e.message); }
}

async function deletePath(path, isDir) {
  if (!(await confirmBox(`Excluir ${isDir ? 'a pasta' : 'o arquivo'} "${path.split('/').pop()}"? Não dá para desfazer.`, 'Excluir'))) return;
  try {
    await ed.closeUnder(path, { save: false });
    await fs.delete(path);
    lsp.fileChanged(path, 3);
    tree.selected = null;
    await refreshDir(dirOf(path));
  } catch (e) { toast('Erro: ' + e.message); }
}

// ----------------------------------------------------------------- paineis
const panelState = { current: 'console', size: 1 }; // size: 0 oculto, 1 normal, 2 grande
const out = { console: null, terminal: null };
const bufs = { console: '', terminal: '' };
const MAX_LINES = 4000;

function showPanel(name) {
  panelState.current = name;
  document.querySelectorAll('#panel-tabs .ptab').forEach((b) => b.classList.toggle('active', b.dataset.p === name));
  document.querySelectorAll('#panel-body > .pane').forEach((p) => p.classList.toggle('active', p.id === 'pane-' + name));
  if (panelState.size === 0) setPanelSize(1);
  updateInputs();
}
function setPanelSize(n) {
  panelState.size = n;
  document.body.classList.remove('panel-0', 'panel-1', 'panel-2');
  document.body.classList.add('panel-' + n);
}
function updateInputs() {
  $('#console-input-row').style.display = state.job && state.job.sink === 'console' ? '' : 'none';
}

const LINK_RE = /(\/root\/[^\s:()[\]'"]+\.(?:java|xml|gradle|kts|properties)):(?:\[(\d+),(\d+)\]|(\d+)(?::(\d+))?)/g;

function lineNode(text) {
  const frag = document.createDocumentFragment();
  let last = 0;
  LINK_RE.lastIndex = 0;
  let m;
  while ((m = LINK_RE.exec(text))) {
    if (m.index > last) frag.append(text.slice(last, m.index));
    const path = m[1];
    const line = +(m[2] || m[4] || 1);
    const col = +(m[3] || m[5] || 1);
    frag.append(el('a', { class: 'link', onclick: () => ed.openFile(path, { line, column: col }).catch((e) => toast(e.message)) }, m[0]));
    last = m.index + m[0].length;
  }
  if (last < text.length) frag.append(text.slice(last));
  return frag;
}

function classify(line) {
  if (/BUILD SUCCESS|BUILD SUCCESSFUL|Tests run:.*Failures: 0, Errors: 0/.test(line)) return 'ok';
  if (/\bERROR\b|error:|Exception|FAILURE|BUILD FAILED|FAILED/.test(line)) return 'err';
  if (/\bWARN(ING)?\b|warning:/.test(line)) return 'warn';
  return '';
}

function write(target, text, cls) {
  const box = out[target];
  const stick = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
  bufs[target] += text;
  const parts = bufs[target].split('\n');
  bufs[target] = parts.pop();
  for (const line of parts) {
    box.insertBefore(el('div', { class: 'ln ' + (cls || classify(line)) }, lineNode(line.replace(/\r/g, ''))), box.lastChild);
  }
  box.lastChild.textContent = bufs[target]; // linha parcial (ex.: "Digite um numero: ")
  while (box.childNodes.length > MAX_LINES) box.removeChild(box.firstChild);
  if (stick) box.scrollTop = box.scrollHeight;
}
export const consoleWrite = (t, cls) => write('console', t, cls);
const termWrite = (t, cls) => write('terminal', t, cls);

function clearPane(target) {
  out[target].textContent = '';
  out[target].append(el('div', { class: 'ln partial' }));
  bufs[target] = '';
}

// ----------------------------------------------------------------- execucao
function setRunning(job) {
  state.job = job;
  document.body.classList.toggle('running', !!job);
  updateInputs();
}

/** Roda um comando mostrando a saida em `sink` ('console' | 'terminal'). */
async function execJob(cmd, cwd, sink, title) {
  if (state.job) return toast('Já há um processo em execução. Pare-o primeiro.');
  const w = sink === 'console' ? consoleWrite : termWrite;
  if (sink === 'console') { showPanel('console'); w('$ ' + (title || cmd) + '\n', 'cmd'); }
  const t0 = Date.now();
  setRunning({ id: -1, sink });
  try {
    const id = await startJob(cmd, cwd, (t) => w(t), (code) => {
      const secs = ((Date.now() - t0) / 1000).toFixed(1);
      w(`\n[processo terminou com código ${code} em ${secs}s]\n`, code === 0 ? 'ok' : 'err');
      setRunning(null);
    });
    if (state.job) state.job.id = id; // pode ja ter terminado
  } catch (e) {
    w('Erro ao iniciar: ' + e.message + '\n', 'err');
    setRunning(null);
  }
}

export async function stopCurrent() {
  if (state.job && state.job.id >= 0) await stopJob(state.job.id).catch(() => {});
}

async function detectKind(root) {
  try {
    const list = await fs.list(root);
    const names = list.map((e) => e.name);
    if (names.includes('pom.xml')) return 'maven';
    if (names.some((n) => /^(build|settings)\.gradle(\.kts)?$/.test(n))) return 'gradle';
    if (names.some((n) => n.endsWith('.java'))) return 'single';
  } catch { /* ignora */ }
  return 'none';
}

const classNameOf = (path, text) => {
  const m = /^\s*package\s+([\w.]+)\s*;/m.exec(text);
  const base = path.split('/').pop().replace(/\.java$/, '');
  return m ? m[1] + '.' + base : base;
};

async function findMain() {
  const active = ed.activePath();
  if (active && active.endsWith('.java')) {
    const txt = await fs.read(active).catch(() => '');
    if (/static\s+void\s+main\s*\(/.test(txt)) return { path: active, cls: classNameOf(active, txt) };
  }
  const r = await runCapture(`grep -rlE "static +void +main *\\(" --include=*.java . 2>/dev/null | head -30`, state.root);
  const files = r.out.split('\n').map((s) => s.trim()).filter((f) => f && !/\/(build|target|out)\//.test(f))
    .map((f) => state.root + '/' + f.replace(/^\.\//, ''));
  if (!files.length) return null;
  let path = files[0];
  if (files.length > 1) {
    path = await chooseBox('Qual classe executar?', files.map((f) => ({ label: f.split('/').pop(), sub: f.replace(state.root + '/', ''), value: f })));
    if (!path) return null;
  }
  const txt = await fs.read(path).catch(() => '');
  return { path, cls: classNameOf(path, txt) };
}

export async function projectAction(action) {
  if (!state.root) return toast('Abra ou crie um projeto primeiro.');
  await ed.saveAll();
  const kind = state.kind;
  if (kind === 'none') return toast('Nenhum projeto Maven/Gradle ou arquivo .java nesta pasta.');
  let cmd;
  if (kind === 'maven') {
    if (action === 'run') {
      const main = await findMain();
      if (!main) return toast('Nenhuma classe com main() encontrada.');
      cmd = `mvn -q -B compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=${q(main.cls)}`;
    } else cmd = { build: 'mvn -B package -DskipTests', test: 'mvn -B test', clean: 'mvn -B clean' }[action];
  } else if (kind === 'gradle') {
    const g = 'gradle --console=plain';
    cmd = { run: `${g} -q run`, build: `${g} build -x test`, test: `${g} test`, clean: `${g} clean` }[action];
  } else { // arquivos .java soltos
    if (action === 'run') {
      const main = await findMain();
      if (!main) return toast('Nenhuma classe com main() encontrada.');
      cmd = `java ${q(main.path.replace(state.root + '/', ''))}`;
    } else if (action === 'build') cmd = `mkdir -p out && javac -d out $(find . -name '*.java' -not -path './out/*')`;
    else return toast('Esta ação só existe em projetos Maven/Gradle.');
  }
  await execJob(cmd, state.root, 'console');
}

// ----------------------------------------------------------------- terminal
const term = { cwd: PROJECTS, history: [], hi: 0 };

async function runTerminal(line) {
  if (!line) return;
  term.history.push(line);
  term.hi = term.history.length;
  termWrite(`${shortCwd()}$ ${line}\n`, 'cmd');
  const cd = /^cd(?:\s+(.*))?$/.exec(line);
  if (cd) {
    const target = (cd[1] || '/root').trim();
    const r = await runCapture(`cd ${q(target)} 2>&1 && pwd`, term.cwd);
    if (r.code === 0) { term.cwd = r.out.trim().split('\n').pop(); updatePrompt(); } else termWrite(r.out, 'err');
    return;
  }
  await execJob(line, term.cwd, 'terminal');
}
const shortCwd = () => term.cwd.replace(/^\/root/, '~');
const updatePrompt = () => { $('#term-prompt').textContent = shortCwd() + '$'; };

// ----------------------------------------------------------------- problemas
export function renderProblems() {
  const box = $('#pane-problems');
  box.textContent = '';
  let total = 0;
  const entries = [...lsp.getDiagnostics().entries()].filter(([, d]) => d.length).sort((a, b) => a[0].localeCompare(b[0]));
  for (const [uri, list] of entries) {
    const path = decodeURIComponent(uri.replace(/^file:\/\//, ''));
    box.append(el('div', { class: 'pfile' }, path.replace(state.root + '/', '')));
    for (const d of list) {
      total++;
      const sev = { 1: 'err', 2: 'warn', 3: 'info', 4: 'info' }[d.severity || 1];
      box.append(el('div', { class: 'prob ' + sev, onclick: () => ed.openFile(path, { line: d.range.start.line + 1, column: d.range.start.character + 1 }).catch((e) => toast(e.message)) },
        el('span', { class: 'psev' }, { err: '✖', warn: '⚠', info: 'ℹ' }[sev]),
        el('span', {}, d.message), el('small', {}, ` ${d.range.start.line + 1}:${d.range.start.character + 1}`)));
    }
  }
  if (!total) box.append(el('div', { class: 'tree-empty' }, 'Nenhum problema detectado.'));
  $('#ptab-problems').textContent = 'Problemas' + (total ? ` (${total})` : '');
  let errors = 0, warnings = 0;
  for (const [, list] of lsp.getDiagnostics()) for (const d of list) { if ((d.severity || 1) === 1) errors++; else if (d.severity === 2) warnings++; }
  $('#st-problems').textContent = `✖ ${errors}  ⚠ ${warnings}`;
}

// ----------------------------------------------------------------- projetos
async function setProject(path) {
  await ed.closeAll();
  state.root = path;
  tree.expanded = new Set([path]);
  tree.cache.clear();
  tree.selected = null;
  state.kind = await detectKind(path);
  localStorage.setItem('cdj.project', path);
  $('#project-name').textContent = path.split('/').pop();
  $('#project-kind').textContent = { maven: 'Maven', gradle: 'Gradle', single: 'Java', none: '—' }[state.kind];
  term.cwd = path; updatePrompt();
  clearPane('console');
  await renderTree();
  renderProblems();
  lsp.startLsp(path);
}

export async function openProject(path) {
  try { await setProject(path); } catch (e) { toast('Erro ao abrir projeto: ' + e.message); }
}

export async function showProjects() {
  let names = [];
  try { names = (await fs.list(PROJECTS)).filter((e) => e.dir && !e.name.startsWith('.')).map((e) => e.name); } catch { /* vazio */ }
  const items = names.map((n) => ({ label: n, sub: PROJECTS + '/' + n, value: { open: PROJECTS + '/' + n } }));
  const r = await chooseBox(items.length ? 'Projetos' : 'Nenhum projeto ainda', items, [{ label: 'Novo projeto…', value: { create: true }, primary: true }]);
  if (!r) return;
  if (r.create) return newProjectDialog();
  await openProject(r.open);
}

export async function newProjectDialog() {
  const name = el('input', { class: 'input', value: 'meu-projeto', autocapitalize: 'off', spellcheck: 'false' });
  const type = el('select', { class: 'input' },
    el('option', { value: 'maven' }, 'Maven (aplicação + JUnit 5)'),
    el('option', { value: 'gradle' }, 'Gradle (aplicação + JUnit 5)'),
    el('option', { value: 'single' }, 'Arquivo Java simples'));
  const pkg = el('input', { class: 'input', value: 'com.example', autocapitalize: 'off', spellcheck: 'false' });
  const content = el('div', { class: 'form' },
    el('label', {}, 'Nome', name), el('label', {}, 'Tipo', type), el('label', {}, 'Pacote', pkg));
  const r = await dialog({ title: 'Novo projeto', content, buttons: [{ label: 'Cancelar', value: null }, { label: 'Criar', value: 'ok', primary: true }] });
  if (r !== 'ok') return;
  const n = name.value.trim();
  const p = pkg.value.trim() || 'com.example';
  if (!/^[A-Za-z][\w.-]*$/.test(n)) return toast('Nome inválido (use letras, números, - e _).');
  if (!/^[a-z][\w]*(\.[a-z][\w]*)*$/.test(p) && type.value !== 'single') return toast('Pacote inválido (ex.: com.exemplo.app).');
  const root = PROJECTS + '/' + n;
  try {
    if (await fs.exists(root)) return toast('Já existe um projeto com esse nome.');
    for (const [rel, text] of Object.entries(newProjectFiles(type.value, n, p))) await fs.write(root + '/' + rel, text);
    await setProject(root);
    const first = { maven: 'src/main/java/' + p.replace(/\./g, '/') + '/App.java', gradle: 'src/main/java/' + p.replace(/\./g, '/') + '/App.java', single: 'Main.java' }[type.value];
    await ed.openFile(root + '/' + first);
    toast('Projeto criado. Toque em ▶ Executar.');
  } catch (e) { toast('Erro ao criar projeto: ' + e.message); }
}

// ----------------------------------------------------------------- configuracoes
export async function settingsDialog() {
  const s = state.settings;
  const theme = el('select', { class: 'input' }, el('option', { value: 'dark' }, 'Escuro'), el('option', { value: 'light' }, 'Claro'));
  theme.value = s.theme;
  const font = el('input', { class: 'input', type: 'number', min: '10', max: '28', value: String(s.fontSize) });
  const tab = el('select', { class: 'input' }, ...[2, 4, 8].map((n) => el('option', { value: String(n) }, String(n))));
  tab.value = String(s.tabSize);
  const auto = el('input', { type: 'checkbox' }); auto.checked = s.autosave;
  const wrap = el('input', { type: 'checkbox' }); wrap.checked = s.wordWrap;
  const restart = el('button', { class: 'btn', onclick: () => { if (state.root) lsp.startLsp(state.root); toast('Reiniciando servidor Java…'); } }, 'Reiniciar servidor Java');
  const stopAll = el('button', { class: 'btn', onclick: async () => { await call('sys.stopAll'); toast('Processos encerrados.'); } }, 'Parar todos os processos');
  const content = el('div', { class: 'form' },
    el('label', {}, 'Tema', theme), el('label', {}, 'Tamanho da fonte', font), el('label', {}, 'Tamanho do tab', tab),
    el('label', { class: 'check' }, auto, ' Salvar automaticamente'),
    el('label', { class: 'check' }, wrap, ' Quebra de linha'),
    el('div', { class: 'row-btns' }, restart, stopAll));
  const r = await dialog({ title: 'Configurações', content, buttons: [{ label: 'Cancelar', value: null }, { label: 'Salvar', value: 'ok', primary: true }] });
  if (r !== 'ok') return;
  state.settings = { theme: theme.value, fontSize: Math.min(28, Math.max(10, +font.value || 14)), tabSize: +tab.value, autosave: auto.checked, wordWrap: wrap.checked };
  localStorage.setItem('cdj.settings', JSON.stringify(state.settings));
  applyTheme(state.settings);
  ed.applySettings(state.settings);
}

// ----------------------------------------------------------------- barra de simbolos (teclado virtual)
function bindTap(btn, fn) {
  // Nao deixa o botao roubar o foco do editor (senao o teclado fecha).
  btn.addEventListener('pointerdown', (e) => e.preventDefault());
  btn.addEventListener('mousedown', (e) => e.preventDefault());
  btn.addEventListener('click', (e) => { e.preventDefault(); fn(); });
}

function buildSymbolBar() {
  const bar = $('#symbolbar');
  const defs = [
    ['Tab', () => ed.command('tab')], ['⇤', () => ed.command('outdent')],
    ['←', () => ed.command('cursorLeft')], ['→', () => ed.command('cursorRight')],
    ['↑', () => ed.command('cursorUp')], ['↓', () => ed.command('cursorDown')],
    ['Início', () => ed.command('cursorHome')], ['Fim', () => ed.command('cursorEnd')],
    ['{', () => ed.insertText('{')], ['}', () => ed.insertText('}')], ['(', () => ed.insertText('(')], [')', () => ed.insertText(')')],
    ['[', () => ed.insertText('[')], [']', () => ed.insertText(']')], [';', () => ed.insertText(';')], ['"', () => ed.insertText('"')],
    ["'", () => ed.insertText("'")], ['<', () => ed.insertText('<')], ['>', () => ed.insertText('>')], ['=', () => ed.insertText('=')],
    ['+', () => ed.insertText('+')], ['-', () => ed.insertText('-')], ['*', () => ed.insertText('*')], ['/', () => ed.insertText('/')],
    ['&', () => ed.insertText('&')], ['|', () => ed.insertText('|')], ['!', () => ed.insertText('!')], ['.', () => ed.insertText('.')],
    [',', () => ed.insertText(',')], ['_', () => ed.insertText('_')],
    ['Desfazer', () => ed.command('undo')], ['Refazer', () => ed.command('redo')],
    ['Copiar', () => ed.copy()], ['Cortar', () => ed.cut()], ['Colar', () => ed.paste()],
    ['Sel. tudo', () => ed.command('selectAll')],
  ];
  for (const [label, fn] of defs) {
    const b = el('button', { class: 'sym' }, label);
    bindTap(b, fn);
    bar.append(b);
  }
}

const MORE_ACTIONS = [
  ['Ir para a definição', () => ed.action('editor.action.revealDefinition')],
  ['Referências', () => ed.action('editor.action.goToReferences')],
  ['Renomear símbolo', () => ed.action('editor.action.rename')],
  ['Formatar código', () => ed.action('editor.action.formatDocument')],
  ['Organizar imports', async () => { if (!(await lsp.organizeImports())) toast('Disponível em arquivos .java'); }],
  ['Símbolos do arquivo', () => ed.action('editor.action.quickOutline')],
  ['Buscar no arquivo', () => ed.action('actions.find')],
  ['Comentar / descomentar linha', () => ed.action('editor.action.commentLine')],
  ['Ir para a linha…', () => ed.action('editor.action.gotoLine')],
  ['Salvar', () => ed.saveActive()],
];

function buildEditorActions() {
  const bar = $('#editor-actions');
  const quick = [
    ['Definição', () => ed.action('editor.action.revealDefinition')],
    ['Corrigir', () => ed.action('editor.action.quickFix')],
  ];
  for (const [label, fn] of quick) {
    const b = el('button', { class: 'abtn' }, label);
    bindTap(b, fn);
    bar.append(b);
  }
  const more = el('button', { class: 'abtn' }, 'Mais ▾');
  more.addEventListener('click', async () => {
    const i = await chooseBox('Ações do editor', MORE_ACTIONS.map(([label], idx) => ({ label, value: idx })));
    if (i !== null && i !== undefined) setTimeout(() => MORE_ACTIONS[i][1](), 50);
  });
  bar.append(more);
}

// ----------------------------------------------------------------- montagem
export function initUi() {
  state.settings = loadSettings();
  applyTheme(state.settings);

  out.console = $('#console-out');
  out.terminal = $('#term-out');
  clearPane('console');
  clearPane('terminal');
  termWrite('Terminal do CodeDroid Java. Cada comando roda em um shell novo (Maven, Gradle, git, java…).\n', 'dim');

  buildSymbolBar();
  buildEditorActions();

  // barra superior
  $('#btn-sidebar').onclick = () => $('#sidebar').classList.toggle('hidden');
  $('#project-btn').onclick = showProjects;
  $('#btn-run').onclick = () => projectAction('run');
  $('#btn-build').onclick = () => projectAction('build');
  $('#btn-test').onclick = () => projectAction('test');
  $('#btn-stop').onclick = stopCurrent;
  $('#btn-panel').onclick = () => setPanelSize((panelState.size + 1) % 3);
  $('#btn-settings').onclick = settingsDialog;
  $('#welcome-new').onclick = newProjectDialog;
  $('#welcome-open').onclick = showProjects;

  // arvore
  $('#tree-newfile').onclick = () => newFile();
  $('#tree-newdir').onclick = () => newFolder();
  $('#tree-refresh').onclick = () => { tree.cache.clear(); renderTree(); };

  // paineis
  document.querySelectorAll('#panel-tabs .ptab').forEach((b) => { b.onclick = () => showPanel(b.dataset.p); });
  $('#panel-clear').onclick = () => { if (panelState.current === 'console') clearPane('console'); else if (panelState.current === 'terminal') clearPane('terminal'); };
  setPanelSize(0); // comeca fechado: abre sozinho ao executar; botao ▤ ou o contador de problemas tambem abrem
  showPanel('console');
  setPanelSize(0);
  $('#st-problems').onclick = () => showPanel('problems');

  const cin = $('#console-input');
  cin.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' || !state.job) return;
    consoleWrite(cin.value + '\n', 'cmd');
    jobInput(state.job.id, cin.value + '\n').catch(() => {});
    cin.value = '';
  });
  $('#console-send').onclick = () => cin.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));

  const tin = $('#term-input');
  tin.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { const v = tin.value.trim(); tin.value = ''; runTerminal(v); }
    else if (e.key === 'ArrowUp' && term.history.length) { term.hi = Math.max(0, term.hi - 1); tin.value = term.history[term.hi] || ''; e.preventDefault(); }
    else if (e.key === 'ArrowDown') { term.hi = Math.min(term.history.length, term.hi + 1); tin.value = term.history[term.hi] || ''; e.preventDefault(); }
  });
  $('#term-up').onclick = () => { if (term.history.length) { term.hi = Math.max(0, term.hi - 1); tin.value = term.history[term.hi] || ''; } };
  $('#term-run').onclick = () => { const v = tin.value.trim(); tin.value = ''; runTerminal(v); };
  updatePrompt();

  lsp.onStatus((t) => { $('#st-lsp').textContent = t; });
  lsp.onDiagnostics(renderProblems);
  ed.onTabsChanged(() => {});
  ed.getEditor().onDidChangeCursorPosition((e) => { $('#st-pos').textContent = `Ln ${e.position.lineNumber}, Col ${e.position.column}`; });

  // teclado virtual: o app chama window.ide.setKeyboard(visivel, temTecladoFisico)
  window.ide = {
    setKeyboard(soft, hw) {
      document.body.classList.toggle('soft', !!soft);
      document.body.classList.toggle('showsym', !!soft && !hw);
    },
  };
}

export async function restoreProject() {
  const last = localStorage.getItem('cdj.project');
  if (last) {
    try { if (await fs.exists(last)) return openProject(last); } catch { /* segue */ }
  }
  $('#project-name').textContent = 'Projetos';
  renderTree();
}
