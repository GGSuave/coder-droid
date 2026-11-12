// Ponto de entrada: carrega o Monaco, monta o editor, o cliente LSP e a interface.
import * as bridge from './bridge.js';
import * as ed from './editor.js';
import * as lsp from './lsp.js';
import * as ui from './ui.js';
import * as templates from './templates.js';

const { installClipboard } = bridge;
const { initUi, restoreProject, loadSettings, toast } = ui;

// Acesso aos modulos pelo console (usado nos testes automatizados em builds debug).
window.__cdj = { bridge, ed, lsp, ui, templates };

installClipboard();

// Monaco 0.57 cria os workers como modulos; so o worker do editor e necessario (Java/XML/etc. sao Monarch).
const base = new URL('monaco/', location.href).href;
window.MonacoEnvironment = {
  getWorker: () => new Worker(base + 'vs/assets/editor.worker.js', { type: 'module', name: 'editor' }),
};

window.require.config({ paths: { vs: base + 'vs' } });

window.require(['vs/editor/editor.main'], async () => {
  try {
    const monaco = window.monaco;
    const settings = loadSettings();
    ed.initEditor(monaco, document.getElementById('editor'), settings);
    lsp.registerProviders(monaco);
    initUi();
    await restoreProject();
  } catch (e) {
    console.error(e);
    toast('Erro ao iniciar a interface: ' + e.message, 10000);
  }
}, (err) => {
  console.error('falha ao carregar o Monaco', err);
  document.body.textContent = 'Falha ao carregar o editor: ' + (err && err.message ? err.message : err);
});
