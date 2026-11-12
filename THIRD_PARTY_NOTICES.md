# Third-party notices / Avisos de terceiros

CodeDroid's own source code is MIT-licensed (see `LICENSE`). The APK it produces **bundles and runs**
the software below, each under its own license. None of it is stored in this repository:
`scripts/fetch_assets.py` downloads it from the upstream sources at build time.

O codigo do CodeDroid e MIT. O APK **embute e executa** os programas abaixo, cada um com sua
propria licenca. Nada disso fica neste repositorio: o `scripts/fetch_assets.py` baixa das fontes originais.

| Component | Used for | License | Source |
|---|---|---|---|
| [code-server](https://github.com/coder/code-server) (Coder) | VS Code served over HTTP | MIT | https://github.com/coder/code-server |
| [VS Code / Code - OSS](https://github.com/microsoft/vscode) (Microsoft) | the editor inside code-server | MIT | https://github.com/microsoft/vscode |
| [proot](https://github.com/termux/proot) (Termux build) | runs the Linux userland without root | **GPL-2.0-or-later** | https://github.com/termux/proot , https://github.com/termux/termux-packages |
| talloc | proot dependency | LGPL-3.0-or-later | https://talloc.samba.org/ |
| libandroid-shmem (Termux) | proot dependency | BSD-3-Clause | https://github.com/termux/libandroid-shmem |
| [Ubuntu Base](https://cdimage.ubuntu.com/ubuntu-base/) 24.04 | the Linux userland (rootfs) | various (Debian/Ubuntu package licenses) | https://ubuntu.com/legal/open-source-licences |
| libatomic (GCC runtime) | needed by the official Node.js build | GPL-3.0 with GCC Runtime Library Exception | https://gcc.gnu.org/ |
| [Node.js](https://nodejs.org/) | JavaScript runtime for your projects | MIT (plus bundled licenses) | https://github.com/nodejs/node |
| [GitHub CLI (`gh`)](https://github.com/cli/cli) | `gh auth login`, Git credential helper | MIT | https://github.com/cli/cli |
| [Mozilla CA bundle](https://curl.se/docs/caextract.html) (via curl.se) | HTTPS certificate roots | MPL-2.0 | https://curl.se/docs/caextract.html |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | unpacking tar.gz on first run | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| [AndroidX WebKit](https://developer.android.com/jetpack/androidx/releases/webkit) | WebView <-> clipboard bridge | Apache-2.0 | https://developer.android.com/jetpack/androidx |

## Notes / Observacoes

- **GPL components (proot, libatomic).** If you redistribute a built APK you are redistributing these
  binaries; you must be able to provide their corresponding source. The upstream URLs above (and the
  exact package files `scripts/fetch_assets.py` downloads from `packages.termux.dev` and
  `archive.ubuntu.com` / `ports.ubuntu.com`) are the source of truth. The only modification made is
  renaming the `libtalloc.so.2` dependency string inside the proot binary to `libtalloc.so`
  (same length), described in `stage_proot()`.
  / Se voce redistribuir um APK, esta redistribuindo esses binarios e deve poder fornecer o codigo-fonte.
- **Trademarks.** "Visual Studio Code" is a trademark of Microsoft. CodeDroid is an independent,
  unofficial project and is not affiliated with or endorsed by Microsoft, Coder, Termux, Canonical,
  GitHub or the Node.js project. code-server uses the Open VSX extension registry by default, not
  Microsoft's Marketplace.
  / Projeto independente e nao oficial, sem afiliacao com as empresas citadas.
