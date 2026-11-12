# Third-party notices / Avisos de terceiros

CodeDroid Java's own source code is MIT-licensed (see `LICENSE`). The APK it produces **bundles and runs**
the software below, each under its own license. None of it is stored in this repository:
`scripts/fetch_assets.py` downloads it from the upstream sources at build time.

O código do CodeDroid Java é MIT. O APK **embute e executa** os programas abaixo, cada um com sua
própria licença. Nada disso fica neste repositório: o `scripts/fetch_assets.py` baixa das fontes originais.

| Component | Used for | License | Source |
|---|---|---|---|
| [Eclipse Temurin](https://adoptium.net/) JDK 25 | compiles and runs your Java programs; runs the language server | GPL-2.0 with Classpath Exception | https://github.com/adoptium/temurin25-binaries |
| [Apache Maven](https://maven.apache.org/) 3.9 | build tool | Apache-2.0 | https://maven.apache.org/ |
| [Gradle](https://gradle.org/) 9.9 | build tool | Apache-2.0 | https://github.com/gradle/gradle |
| [Eclipse JDT Language Server](https://github.com/eclipse-jdtls/eclipse.jdt.ls) | code intelligence (errors, completion, hover…) | EPL-2.0 | https://github.com/eclipse-jdtls/eclipse.jdt.ls |
| [Monaco Editor](https://github.com/microsoft/monaco-editor) | the code editor in the UI | MIT | https://github.com/microsoft/monaco-editor |
| [proot](https://github.com/termux/proot) (Termux build) | runs the Linux userland without root | **GPL-2.0-or-later** | https://github.com/termux/proot , https://github.com/termux/termux-packages |
| talloc | proot dependency | LGPL-3.0-or-later | https://talloc.samba.org/ |
| libandroid-shmem (Termux) | proot dependency | BSD-3-Clause | https://github.com/termux/libandroid-shmem |
| [Ubuntu Base](https://cdimage.ubuntu.com/ubuntu-base/) 24.04 | the Linux userland (rootfs) | various (Debian/Ubuntu package licenses) | https://ubuntu.com/legal/open-source-licences |
| git (installed from Ubuntu with `apt`) | version control in the terminal | GPL-2.0 | https://git-scm.com/ |
| [Mozilla CA bundle](https://curl.se/docs/caextract.html) (via curl.se) | HTTPS certificate roots | MPL-2.0 | https://curl.se/docs/caextract.html |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | unpacking tar.gz on first run | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| [AndroidX WebKit](https://developer.android.com/jetpack/androidx/releases/webkit) | asset loader and WebView bridge | Apache-2.0 | https://developer.android.com/jetpack/androidx |

Maven and Gradle download further libraries (plugins and your projects' dependencies) from Maven Central at run time, each under its own license.
/ O Maven e o Gradle baixam outras bibliotecas do Maven Central durante o uso, cada uma com sua licença.

## Notes / Observações

- **GPL / EPL components (proot, JDK, git, JDT LS).** If you redistribute a built APK you are redistributing these
  binaries; you must be able to provide their corresponding source. The upstream URLs above (and the exact files
  `scripts/fetch_assets.py` downloads) are the source of truth. The only modification made is renaming the
  `libtalloc.so.2` dependency string inside the proot binary to `libtalloc.so` (same length), described in `stage_proot()`.
  The Monaco worker is copied under a stable file name and unused language-service workers are removed; its code is not changed.
  / Se você redistribuir um APK, está redistribuindo esses binários e deve poder fornecer o código-fonte.
- **Trademarks.** Java and OpenJDK are trademarks of Oracle and/or its affiliates; Eclipse is a trademark of the Eclipse Foundation;
  Gradle is a trademark of Gradle, Inc.; Apache Maven is a trademark of the Apache Software Foundation. CodeDroid Java is an independent,
  unofficial project and is not affiliated with or endorsed by any of them.
  / Projeto independente e não oficial, sem afiliação com as empresas e fundações citadas.
