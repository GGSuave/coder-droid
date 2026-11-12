// Cliente LSP minimo: liga o Eclipse JDT LS (rodando via proot) ao Monaco.
import { call, on, fs } from './bridge.js';
import * as ed from './editor.js';

let monaco = null;
let ready = false;
let nextId = 1;
const pending = new Map();
const tracking = new Map();   // uri -> disposables
const versions = new Map();   // uri -> versao
const opened = new Set();     // uris com didOpen enviado
const timers = new Map();     // uri -> timer do didChange
const diagnostics = new Map(); // uri -> LSP diagnostics[]
let statusCb = () => {};
let diagCb = () => {};
let progressActive = 0;

export const onStatus = (fn) => { statusCb = fn; };
export const onDiagnostics = (fn) => { diagCb = fn; };
export const getDiagnostics = () => diagnostics;
export const isReady = () => ready;

const EXEC_CMD = 'cdj.lsp.exec';

// ----------------------------------------------------------------- conversoes
const toRange = (r) => new monaco.Range(r.start.line + 1, r.start.character + 1, r.end.line + 1, r.end.character + 1);
const toLspRange = (r) => ({
  start: { line: r.startLineNumber - 1, character: r.startColumn - 1 },
  end: { line: r.endLineNumber - 1, character: r.endColumn - 1 },
});
const toLspPos = (p) => ({ line: p.lineNumber - 1, character: p.column - 1 });
const uriOf = (model) => model.uri.toString();
const pathOfUri = (uri) => monaco.Uri.parse(uri).path;

const SEVERITY = () => ({ 1: monaco.MarkerSeverity.Error, 2: monaco.MarkerSeverity.Warning, 3: monaco.MarkerSeverity.Info, 4: monaco.MarkerSeverity.Hint });

function md(doc) {
  if (!doc) return undefined;
  if (typeof doc === 'string') return doc;
  return { value: doc.value || '' };
}

function hoverContent(c) {
  if (!c) return [];
  const list = Array.isArray(c) ? c : [c];
  return list.map((x) => {
    if (typeof x === 'string') return { value: x };
    if (x.language) return { value: '```' + x.language + '\n' + x.value + '\n```' };
    return { value: x.value || '' };
  });
}

// ----------------------------------------------------------------- transporte
const send = (obj) => call('lsp.send', { json: JSON.stringify(obj) }).catch(() => {});

function request(method, params, { force = false, timeout = 45000 } = {}) {
  if (!ready && !force) return Promise.resolve(null);
  return new Promise((res, rej) => {
    const id = nextId++;
    const t = setTimeout(() => { pending.delete(id); rej(new Error('tempo esgotado: ' + method)); }, timeout);
    pending.set(id, {
      res: (v) => { clearTimeout(t); res(v); },
      rej: (e) => { clearTimeout(t); rej(e); },
    });
    send({ jsonrpc: '2.0', id, method, params });
  });
}
const notify = (method, params) => send({ jsonrpc: '2.0', method, params });
const respond = (id, result) => send({ jsonrpc: '2.0', id, result });

on('lsp.msg', (d) => {
  let m;
  try { m = JSON.parse(d.json); } catch { return; }
  handle(m);
});
on('lsp.exit', (d) => {
  ready = false;
  pending.forEach((p) => p.rej(new Error('servidor encerrado')));
  pending.clear();
  opened.clear();
  statusCb('Java: servidor encerrado (código ' + d.code + ')');
});

function handle(m) {
  if (m.id !== undefined && m.method === undefined) {
    const p = pending.get(m.id);
    if (p) { pending.delete(m.id); if (m.error) p.rej(new Error(m.error.message)); else p.res(m.result); }
    return;
  }
  if (m.method && m.id !== undefined) return serverRequest(m);
  notification(m);
}

function serverRequest(m) {
  switch (m.method) {
    case 'workspace/configuration':
      return respond(m.id, (m.params.items || []).map(() => null));
    case 'workspace/applyEdit':
      return applyWorkspaceEdit(m.params.edit).then(() => respond(m.id, { applied: true })).catch(() => respond(m.id, { applied: false }));
    default: // registerCapability, workDoneProgress/create, showMessageRequest, executeClientCommand...
      return respond(m.id, null);
  }
}

function notification(m) {
  switch (m.method) {
    case 'textDocument/publishDiagnostics':
      diagnostics.set(m.params.uri, m.params.diagnostics || []);
      applyMarkers(m.params.uri);
      diagCb();
      break;
    case 'language/status': {
      const t = m.params.type;
      if (t === 'ServiceReady' || t === 'Started') statusCb('Java: pronto');
      else if (t === 'Error') statusCb('Java: erro — ' + (m.params.message || ''));
      else if (m.params.message) statusCb('Java: ' + String(m.params.message).slice(0, 80));
      break;
    }
    case 'language/progressReport': {
      const p = m.params;
      if (p.complete) statusCb('Java: pronto');
      else statusCb('Java: ' + (p.task || '') + (p.subTask ? ' — ' + p.subTask : ''));
      break;
    }
    case '$/progress': {
      const v = m.params.value || {};
      if (v.kind === 'begin') { progressActive++; statusCb('Java: ' + (v.title || '') + (v.message ? ' — ' + v.message : '')); }
      else if (v.kind === 'report' && v.message) statusCb('Java: ' + v.message);
      else if (v.kind === 'end') { progressActive = Math.max(0, progressActive - 1); if (!progressActive) statusCb('Java: pronto'); }
      break;
    }
    default: break;
  }
}

// ----------------------------------------------------------------- marcadores
function applyMarkers(uri) {
  const model = monaco.editor.getModel(monaco.Uri.parse(uri));
  if (!model) return;
  const sev = SEVERITY();
  const list = (diagnostics.get(uri) || []).map((d) => ({
    severity: sev[d.severity || 1],
    message: d.message,
    source: d.source,
    code: d.code != null ? String(d.code) : undefined,
    startLineNumber: d.range.start.line + 1,
    startColumn: d.range.start.character + 1,
    endLineNumber: d.range.end.line + 1,
    endColumn: Math.max(d.range.end.character + 1, d.range.start.character + 2),
  }));
  monaco.editor.setModelMarkers(model, 'jdt', list);
}

// ----------------------------------------------------------------- sincronizacao de documentos
function didOpen(model) {
  const uri = uriOf(model);
  if (opened.has(uri)) return;
  opened.add(uri);
  notify('textDocument/didOpen', { textDocument: { uri, languageId: 'java', version: versions.get(uri) || 1, text: model.getValue() } });
}

function didChange(model) {
  const uri = uriOf(model);
  timers.delete(uri);
  if (!opened.has(uri)) return;
  const v = (versions.get(uri) || 1) + 1;
  versions.set(uri, v);
  notify('textDocument/didChange', { textDocument: { uri, version: v }, contentChanges: [{ text: model.getValue() }] });
}

/** Envia imediatamente a edicao pendente (antes de pedir completar/hover etc.). */
function flush(model) {
  const uri = uriOf(model);
  if (timers.has(uri)) { clearTimeout(timers.get(uri)); didChange(model); }
}

function track(model) {
  if (model.getLanguageId() !== 'java' || model.uri.scheme !== 'file') return;
  const uri = uriOf(model);
  if (tracking.has(uri)) return;
  versions.set(uri, 1);
  const subs = [
    model.onDidChangeContent(() => {
      if (!ready) return;
      clearTimeout(timers.get(uri));
      timers.set(uri, setTimeout(() => didChange(model), 150));
    }),
    model.onWillDispose(() => {
      clearTimeout(timers.get(uri)); timers.delete(uri);
      if (ready && opened.has(uri)) notify('textDocument/didClose', { textDocument: { uri } });
      opened.delete(uri);
      subs.forEach((s) => s.dispose());
      tracking.delete(uri);
    }),
  ];
  tracking.set(uri, subs);
  if (ready) didOpen(model);
  applyMarkers(uri);
}

// ----------------------------------------------------------------- ciclo de vida
function javaSettings() {
  return {
    configuration: { updateBuildConfiguration: 'automatic', runtimes: [{ name: 'JavaSE-25', path: '/opt/jdk', default: true }] },
    import: { gradle: { home: '/opt/gradle', wrapper: { enabled: true } }, maven: { enabled: true } },
    autobuild: { enabled: true },
    completion: { enabled: true, guessMethodArguments: true, favoriteStaticMembers: ['org.junit.jupiter.api.Assertions.*', 'java.util.Objects.requireNonNull'] },
    signatureHelp: { enabled: true },
    format: { enabled: true },
    referencesCodeLens: { enabled: false },
    implementationsCodeLens: { enabled: false },
    maven: { downloadSources: false },
    eclipse: { downloadSources: false },
    sources: { organizeImports: { starThreshold: 99, staticStarThreshold: 99 } },
  };
}

function initParams(root) {
  const rootUri = monaco.Uri.file(root).toString();
  return {
    processId: null,
    clientInfo: { name: 'CodeDroid Java' },
    rootUri,
    workspaceFolders: [{ uri: rootUri, name: root.split('/').pop() }],
    capabilities: {
      workspace: {
        applyEdit: true,
        workspaceEdit: { documentChanges: true, resourceOperations: ['create', 'rename', 'delete'] },
        didChangeWatchedFiles: { dynamicRegistration: true },
        executeCommand: { dynamicRegistration: false },
        workspaceFolders: true,
        configuration: true,
      },
      window: { workDoneProgress: true },
      textDocument: {
        synchronization: { didSave: true, dynamicRegistration: false },
        completion: {
          completionItem: {
            snippetSupport: true, documentationFormat: ['markdown', 'plaintext'], labelDetailsSupport: true,
            resolveSupport: { properties: ['documentation', 'detail', 'additionalTextEdits'] },
          },
          contextSupport: true,
        },
        hover: { contentFormat: ['markdown', 'plaintext'] },
        signatureHelp: { signatureInformation: { documentationFormat: ['markdown', 'plaintext'], parameterInformation: { labelOffsetSupport: true } } },
        definition: { linkSupport: true },
        references: {},
        documentSymbol: { hierarchicalDocumentSymbolSupport: true },
        formatting: {},
        rename: { prepareSupport: false },
        codeAction: {
          isPreferredSupport: true,
          codeActionLiteralSupport: { codeActionKind: { valueSet: ['', 'quickfix', 'refactor', 'refactor.extract', 'refactor.inline', 'refactor.rewrite', 'source', 'source.organizeImports'] } },
        },
        publishDiagnostics: { relatedInformation: false },
      },
    },
    initializationOptions: {
      bundles: [],
      workspaceFolders: [rootUri],
      settings: { java: javaSettings() },
      extendedClientCapabilities: {
        progressReportProvider: true, classFileContentsSupport: true, resolveAdditionalTextEditsSupport: true,
        executeClientCommandSupport: true, snippetEditSupport: true,
      },
    },
  };
}

export async function startLsp(root) {
  await stopLsp();
  statusCb('Java: iniciando…');
  try {
    await call('lsp.start', { root });
    await request('initialize', initParams(root), { force: true, timeout: 120000 });
    notify('initialized', {});
    notify('workspace/didChangeConfiguration', { settings: { java: javaSettings() } });
    ready = true;
    for (const m of ed.modelsOfLanguage('java')) if (m.uri.scheme === 'file') didOpen(m);
    statusCb('Java: carregando projeto…');
  } catch (e) {
    ready = false;
    statusCb('Java: falha ao iniciar — ' + e.message);
  }
}

export async function stopLsp() {
  ready = false;
  pending.forEach((p) => p.rej(new Error('reiniciando')));
  pending.clear();
  opened.clear();
  progressActive = 0;
  for (const uri of diagnostics.keys()) {
    const model = monaco.editor.getModel(monaco.Uri.parse(uri));
    if (model) monaco.editor.setModelMarkers(model, 'jdt', []);
  }
  diagnostics.clear();
  diagCb();
  await call('lsp.stop').catch(() => {});
}

/** Avisa o servidor que um arquivo foi criado (1), alterado (2) ou apagado (3) fora do editor. */
export function fileChanged(path, type = 2) {
  if (!ready) return;
  notify('workspace/didChangeWatchedFiles', { changes: [{ uri: monaco.Uri.file(path).toString(), type }] });
}

// ----------------------------------------------------------------- edicoes de workspace
async function createFileFromUri(uri) {
  await fs.write(pathOfUri(uri), '');
}

export async function applyWorkspaceEdit(we) {
  if (!we) return;
  const byUri = new Map();
  const add = (uri, edits) => { if (!byUri.has(uri)) byUri.set(uri, []); byUri.get(uri).push(...edits); };
  for (const dc of we.documentChanges || []) {
    if (dc.textDocument) add(dc.textDocument.uri, dc.edits);
    else if (dc.kind === 'create') await createFileFromUri(dc.uri);
    else if (dc.kind === 'rename') { await fs.rename(pathOfUri(dc.oldUri), pathOfUri(dc.newUri)); await ed.closeUnder(pathOfUri(dc.oldUri)); }
    else if (dc.kind === 'delete') { await fs.delete(pathOfUri(dc.uri)); await ed.closeUnder(pathOfUri(dc.uri), { save: false }); }
  }
  for (const [uri, edits] of Object.entries(we.changes || {})) add(uri, edits);
  for (const [uri, edits] of byUri) {
    const model = await ed.getModelForUri(uri);
    model.pushEditOperations([], edits.map((e) => ({ range: toRange(e.range), text: e.newText })), () => null);
  }
}

/** Converte para o formato do Monaco (usado por renomear / correcoes rapidas). */
async function toMonacoEdit(we) {
  const list = [];
  const push = async (uri, edits) => {
    await ed.getModelForUri(uri);
    for (const e of edits) list.push({ resource: monaco.Uri.parse(uri), textEdit: { range: toRange(e.range), text: e.newText }, versionId: undefined });
  };
  for (const dc of we.documentChanges || []) {
    if (dc.textDocument) await push(dc.textDocument.uri, dc.edits);
    else if (dc.kind === 'create') await createFileFromUri(dc.uri);
  }
  for (const [uri, edits] of Object.entries(we.changes || {})) await push(uri, edits);
  return { edits: list };
}

async function execLspCommand(cmd) {
  if (!cmd) return;
  if (cmd.command === 'java.apply.workspaceEdit') {
    for (const we of cmd.arguments || []) await applyWorkspaceEdit(we);
    return;
  }
  const res = await request('workspace/executeCommand', { command: cmd.command, arguments: cmd.arguments || [] });
  if (res && (res.changes || res.documentChanges)) await applyWorkspaceEdit(res);
}

export async function organizeImports() {
  const model = ed.getEditor()?.getModel();
  if (!model || model.getLanguageId() !== 'java') return false;
  flush(model);
  const r = await request('textDocument/codeAction', {
    textDocument: { uri: uriOf(model) },
    range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
    context: { diagnostics: [], only: ['source.organizeImports'] },
  });
  for (const a of r || []) {
    if (a.edit) await applyWorkspaceEdit(a.edit);
    else if (a.command) await execLspCommand(typeof a.command === 'string' ? a : a.command);
  }
  return true;
}

// ----------------------------------------------------------------- provedores do Monaco
export function registerProviders(mon) {
  monaco = mon;
  const L = monaco.languages;
  const K = monaco.languages.CompletionItemKind;
  const KINDS = [null, K.Text, K.Method, K.Function, K.Constructor, K.Field, K.Variable, K.Class, K.Interface, K.Module, K.Property,
    K.Unit, K.Value, K.Enum, K.Keyword, K.Snippet, K.Color, K.File, K.Reference, K.Folder, K.EnumMember, K.Constant, K.Struct,
    K.Event, K.Operator, K.TypeParameter];

  ed.setVirtualLoader(async (uri) => {
    const r = await request('java/classFileContents', { uri });
    return typeof r === 'string' ? r : (r && r.content) || '// (fonte indisponivel)';
  });

  monaco.editor.onDidCreateModel(track);
  monaco.editor.getModels().forEach(track);
  ed.onSaved((path) => {
    const model = monaco.editor.getModel(monaco.Uri.file(path));
    if (model && ready && opened.has(uriOf(model))) notify('textDocument/didSave', { textDocument: { uri: uriOf(model) } });
    if (/(^|\/)(pom\.xml|build\.gradle(\.kts)?|settings\.gradle(\.kts)?)$/.test(path)) fileChanged(path, 2);
  });

  monaco.editor.registerCommand(EXEC_CMD, (_accessor, cmd) => execLspCommand(cmd).catch((e) => console.error(e)));

  monaco.editor.registerEditorOpener({
    openCodeEditor(_source, resource, selectionOrPosition) {
      const sel = selectionOrPosition;
      const pos = sel ? { line: sel.startLineNumber || sel.lineNumber, column: sel.startColumn || sel.column } : undefined;
      ed.openUri(resource.toString(), pos);
      return true;
    },
  });

  const toLocations = async (r) => {
    if (!r) return [];
    const list = Array.isArray(r) ? r : [r];
    const out = [];
    for (const x of list) {
      const uri = x.uri || x.targetUri;
      const range = x.range || x.targetSelectionRange || x.targetRange;
      if (!uri || !range) continue;
      try { await ed.getModelForUri(uri); } catch { continue; }
      out.push({ uri: monaco.Uri.parse(uri), range: toRange(range) });
    }
    return out;
  };

  const syncRequest = async (model, method, params, opts) => {
    flush(model);
    return request(method, { textDocument: { uri: uriOf(model) }, ...params }, opts);
  };

  L.registerCompletionItemProvider('java', {
    triggerCharacters: ['.', '@', '#'],
    async provideCompletionItems(model, position, context) {
      const r = await syncRequest(model, 'textDocument/completion', {
        position: toLspPos(position),
        context: { triggerKind: (context.triggerKind ?? 0) + 1, triggerCharacter: context.triggerCharacter },
      }).catch(() => null);
      if (!r) return { suggestions: [] };
      const items = Array.isArray(r) ? r : r.items || [];
      const word = model.getWordUntilPosition(position);
      const fallback = new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
      return {
        incomplete: !!r.isIncomplete,
        suggestions: items.map((it) => {
          const te = it.textEdit;
          const range = te ? toRange(te.range || te.replace) : fallback;
          const label = it.labelDetails
            ? { label: it.label, detail: it.labelDetails.detail, description: it.labelDetails.description }
            : it.label;
          return {
            label,
            kind: KINDS[it.kind] ?? K.Text,
            detail: it.detail,
            documentation: md(it.documentation),
            insertText: te ? te.newText : (it.insertText || it.label),
            insertTextRules: it.insertTextFormat === 2 ? L.CompletionItemInsertTextRule.InsertAsSnippet : undefined,
            range,
            filterText: it.filterText,
            sortText: it.sortText,
            preselect: it.preselect,
            commitCharacters: it.commitCharacters,
            additionalTextEdits: (it.additionalTextEdits || []).map((e) => ({ range: toRange(e.range), text: e.newText })),
            _lsp: it,
          };
        }),
      };
    },
    async resolveCompletionItem(item) {
      if (!item._lsp) return item;
      try {
        const r = await request('completionItem/resolve', item._lsp);
        if (r) {
          if (r.additionalTextEdits) item.additionalTextEdits = r.additionalTextEdits.map((e) => ({ range: toRange(e.range), text: e.newText }));
          if (r.documentation) item.documentation = md(r.documentation);
          if (r.detail) item.detail = r.detail;
        }
      } catch { /* mantem o item original */ }
      return item;
    },
  });

  L.registerHoverProvider('java', {
    async provideHover(model, position) {
      const r = await syncRequest(model, 'textDocument/hover', { position: toLspPos(position) }).catch(() => null);
      if (!r || !r.contents) return null;
      return { contents: hoverContent(r.contents), range: r.range ? toRange(r.range) : undefined };
    },
  });

  L.registerDefinitionProvider('java', {
    async provideDefinition(model, position) {
      return toLocations(await syncRequest(model, 'textDocument/definition', { position: toLspPos(position) }).catch(() => null));
    },
  });

  L.registerReferenceProvider('java', {
    async provideReferences(model, position) {
      return toLocations(await syncRequest(model, 'textDocument/references', { position: toLspPos(position), context: { includeDeclaration: true } }).catch(() => null));
    },
  });

  L.registerSignatureHelpProvider('java', {
    signatureHelpTriggerCharacters: ['(', ','],
    async provideSignatureHelp(model, position) {
      const r = await syncRequest(model, 'textDocument/signatureHelp', { position: toLspPos(position) }).catch(() => null);
      if (!r || !r.signatures || !r.signatures.length) return null;
      return {
        value: {
          activeSignature: r.activeSignature || 0,
          activeParameter: r.activeParameter || 0,
          signatures: r.signatures.map((s) => ({
            label: s.label,
            documentation: md(s.documentation),
            parameters: (s.parameters || []).map((p) => ({ label: p.label, documentation: md(p.documentation) })),
          })),
        },
        dispose() {},
      };
    },
  });

  L.registerDocumentFormattingEditProvider('java', {
    async provideDocumentFormattingEdits(model, options) {
      const r = await syncRequest(model, 'textDocument/formatting', { options: { tabSize: options.tabSize, insertSpaces: options.insertSpaces } }, { timeout: 60000 }).catch(() => null);
      return (r || []).map((e) => ({ range: toRange(e.range), text: e.newText }));
    },
  });

  L.registerRenameProvider('java', {
    async provideRenameEdits(model, position, newName) {
      const we = await syncRequest(model, 'textDocument/rename', { position: toLspPos(position), newName }, { timeout: 60000 });
      if (!we) return { edits: [] };
      return toMonacoEdit(we);
    },
  });

  L.registerDocumentSymbolProvider('java', {
    async provideDocumentSymbols(model) {
      const r = await syncRequest(model, 'textDocument/documentSymbol', {}).catch(() => null);
      const conv = (s) => s.location
        ? { name: s.name, detail: s.containerName || '', kind: s.kind - 1, tags: [], range: toRange(s.location.range), selectionRange: toRange(s.location.range) }
        : { name: s.name, detail: s.detail || '', kind: s.kind - 1, tags: [], range: toRange(s.range), selectionRange: toRange(s.selectionRange), children: (s.children || []).map(conv) };
      return (r || []).map(conv);
    },
  });

  const sevName = (s) => ({ [monaco.MarkerSeverity.Error]: 1, [monaco.MarkerSeverity.Warning]: 2, [monaco.MarkerSeverity.Info]: 3, [monaco.MarkerSeverity.Hint]: 4 }[s] || 1);
  L.registerCodeActionProvider('java', {
    async provideCodeActions(model, range, context) {
      const diags = (context.markers || []).map((m) => ({
        range: toLspRange(m), message: m.message, severity: sevName(m.severity), code: m.code, source: m.source,
      }));
      const r = await syncRequest(model, 'textDocument/codeAction', {
        range: toLspRange(range), context: { diagnostics: diags, only: context.only ? [context.only] : undefined },
      }).catch(() => null);
      const actions = [];
      for (const a of r || []) {
        if (!a) continue;
        const act = { title: a.title, kind: a.kind, isPreferred: a.isPreferred };
        if (typeof a.command === 'string') act.command = { id: EXEC_CMD, title: a.title, arguments: [a] };
        else {
          if (a.edit) act.edit = await toMonacoEdit(a.edit);
          if (a.command) act.command = { id: EXEC_CMD, title: a.command.title || a.title, arguments: [a.command] };
        }
        actions.push(act);
      }
      return { actions, dispose() {} };
    },
  });
}
