# CodeDroid Java

**A complete Java IDE that runs 100% locally on your Android phone or tablet: JDK 25, Maven, Gradle and a real language server, in a single APK.**
No server to set up, no Termux session, no browser tab.

> 🇧🇷 [Leia em português](README.pt-BR.md)
>
> 🌿 This is the **`java-ide`** branch. The original **CodeDroid** (VS Code running inside an APK) lives on the [`main`](https://github.com/GGSuave/coder-droid/tree/main) branch.
>
> 🤖 **Built with [Claude](https://claude.com/claude-code)**: designed, written, debugged and tested by Claude (Anthropic) in [Claude Code](https://claude.com/claude-code), directed by its author through conversation. See [Built with Claude](#built-with-claude).

---

## What is it?

A Java-only IDE built for the touchscreen. You create a Maven or Gradle project, edit with code completion and live error checking, press **▶ Run**, and see the program's output. Everything, including the compiler, the build tools and the language server, is inside the app.

It reuses the idea of CodeDroid (a full Ubuntu userland inside the app through `proot`), but instead of VS Code it has its **own interface** built on the [Monaco](https://microsoft.github.io/monaco-editor/) editor, talking to the [Eclipse JDT Language Server](https://github.com/eclipse-jdtls/eclipse.jdt.ls).

## Highlights

- **Real Java tooling:** Temurin **JDK 25**, **Maven 3.9**, **Gradle 9.9** and the Eclipse JDT language server, all bundled.
- **Language features (via LSP):** live compile errors, code completion with auto-import, hover documentation, quick fixes, go to definition (even into the JDK sources), references, rename, formatting, organize imports.
- **Run / Compile / Test / Stop** for Maven, Gradle and single `.java` files. Output goes to a console where file locations are clickable.
- **Console input:** programs that read the keyboard (`Scanner`) work: type in the console and press Enter.
- **Project wizard:** Maven or Gradle app with a JUnit 5 test, or a plain Java file.
- **Made for touch and for tablets:** landscape layout, a symbol bar (`{ } ( ) [ ] ; " < >`, arrows, copy/paste…) that shows only with the on-screen keyboard and no physical keyboard, working copy/paste, dark and light themes.
- **Problems panel, terminal (one command at a time) and file tree** with create / rename / delete.
- **No network port.** The UI talks to the app through an internal WebView bridge (see [Security](#security)).
- **Behaves like a good Android app:** closing the window in the app switcher stops the language server and every build. The notification has a *Stop* button.

## How it works

```
┌─────────────────────────── Android app  (dev.codedroid.java) ───────────────────────────┐
│                                                                                         │
│  WebView ── https://appassets.androidplatform.net/assets/web/   (HTML/JS, no server)    │
│     Monaco editor · file tree · tabs · console · problems · terminal · LSP client       │
│        ▲                                                                                │
│        │  WebMessageListener "ideBridge"  (JSON-RPC, restricted to the app's origin)    │
│        ▼                                                                                │
│  Kotlin backend                                                                         │
│     FileApi      read/write files under /root (path-checked)                            │
│     JobManager   runs mvn / gradle / java, streams output, kills the whole tree         │
│     LspSession   runs the JDT language server over stdio and forwards the messages      │
│        │                                                                                │
│        └─ spawns ▶ libproot.so   ← exec'd from nativeLibraryDir (see below)             │
│                       └─ rootfs/   Ubuntu 24.04 in the app's private files dir          │
│                            ├ /opt/jdk  /opt/maven  /opt/gradle  /opt/jdtls              │
│                            └ /root/projects        your projects (kept across updates)  │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

**First launch** (`Installer.kt`): unpacks the Ubuntu base and each tool (JDK, Maven, Gradle, language server) into the app's private storage, writes the small config files the sandbox needs, and installs `git` through `apt` if there is internet. Each component has its own version marker, so updating one tool doesn't redo the rest or erase what you installed.

**Opening a project:** the UI starts the language server (`lsp.start`) and speaks the Language Server Protocol to it through the bridge. A compact LSP client (`js/lsp.js`) maps the server's answers onto Monaco's providers.

**Running:** the **▶ Run** button saves everything, picks the build tool and runs, for example, `mvn -q compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=…` or `gradle -q run`, streaming the output to the console.

### The Android problems this project had to solve

| Problem | Solution |
|---|---|
| **W^X**: Android 10+ forbids running files from an app's data dir (apps with `targetSdk ≥ 29`) | `proot` ships as `lib*.so` so it lands in `nativeLibraryDir`, the one place apps may exec from; `targetSdk = 28`, like Termux. **Not eligible for the Play Store.** |
| No root, no `chroot` | **proot** (the Termux build), user-space `chroot` that works under Android's seccomp policy. |
| `proot` with `PROOT_NO_SECCOMP=1` is ~10× slower (`javac` 9.9 s vs 0.8 s) | The app never sets it; proot uses its seccomp acceleration. |
| Latest JDT LS requires **Java 25**, but Ubuntu 24.04 only has 21 | Bundles the Temurin 25 tarball instead of using `apt`. |
| `Process.destroy()` does not stop proot's children | Every job gets a `CD_JOB=n` marker inherited by its children; stopping scans `/proc` and `SIGKILL`s everything with the marker. The service also kills the whole process tree when the window is closed. |
| Android kills the WebView renderer under memory pressure, and that **kills the whole app** | `onRenderProcessGone` rebuilds the WebView; the language server and builds are untouched. |
| Monaco 0.57 ships workers as ES modules with hashed names | `fetch_assets.py` gives the editor worker a stable name and drops the unused language-service workers. |
| A phone in landscape is only ~364 CSS px tall | Compact layout: tabs and actions share one row, the bottom panel opens on demand, and the on-screen keyboard hides the bars. |

## Requirements

| | |
|---|---|
| Device | Android **8.0+** (API 26). **arm64** phones/tablets use the `arm64` flavor; `x64` is for emulators. |
| Tested on | Android 15 on an x86_64 emulator. The arm64 build is new and still being tested on real hardware. |
| Space | ~350 MB for the APK and roughly 1 GB once unpacked, plus your projects and the Maven/Gradle caches. |
| RAM | 6 GB or more recommended (the language server uses up to 1 GB). |
| Build host | JDK 17+ (Android Studio's works), Android SDK (platform **36**), **Python 3.9+**, internet. |

## Build from source

The repository holds only source (~250 KB). The binaries that go into the APK (Ubuntu, JDK, Maven, Gradle, language server, Monaco, proot) are downloaded by a script.

```bash
git clone -b java-ide https://github.com/GGSuave/coder-droid.git
cd coder-droid

# 1. download everything the APK embeds (≈ 400 MB per architecture)
python scripts/fetch_assets.py --abi arm64      # phones/tablets  (or: --abi x64 / --abi all)

# 2. build a debug APK
./gradlew :app:assembleArm64Debug               # Windows: gradlew.bat ...

# 3. install
adb install app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

Point Gradle at your SDK with `ANDROID_HOME`, or a `local.properties` file with `sdk.dir=/path/to/Android/sdk`.

### A signed release

```bash
cp keystore.properties.example keystore.properties   # then edit it (git-ignored)
keytool -genkeypair -keystore codedroid-release.jks -alias codedroid -keyalg RSA -keysize 4096 -validity 10000
./gradlew :app:assembleArm64Release
# → app/build/outputs/apk/arm64/release/app-arm64-release.apk
```

> ⚠️ Keep the `.jks` and its passwords **outside** the repository and back them up: without the key you can't ship updates that install over an existing install.

## Using it

1. **First open** takes about a minute. Allow notifications and accept the battery-optimisation exemption (otherwise Android may stop the language server in the background).
2. Tap **Novo projeto**, pick *Maven* or *Gradle*, name it and tap **Criar**.
3. Edit the code. Errors appear as you type; completion pops up after `.`. **Definição** and **Corrigir** are in the editor bar; **Mais ▾** has rename, format, organize imports and more.
4. Tap **▶ Executar**. The console opens with the output. If the program reads input, type in the field at the bottom of the console.
5. **✔ Testar** runs the JUnit tests. **🔨 Compilar** builds the project. **■ Parar** stops whatever is running.
6. The first Maven/Gradle run needs internet to download plugins and dependencies.

Updating the APK (same signing key) keeps your `/root` (projects and caches).

## Project layout

```
app/src/main/kotlin/dev/codedroid/java/
  MainActivity.kt   WebView host (asset loader, bridge), keyboard detection, renderer recovery
  IdeBridge.kt      JSON-RPC bridge UI <-> app (files, jobs, LSP, clipboard)
  FileApi.kt        file operations, limited to /root, symlink-safe delete
  Jobs.kt           JobManager (mvn/gradle/java) and LspSession (JDT LS over stdio)
  Installer.kt      first-run unpacking, versioned components, configuration, git via apt
  Proot.kt          builds the proot command line (JAVA_HOME, PATH, ...)
  Tools.kt          process-tree helpers (kill by marker / by descendants)
  IdeService.kt     foreground service; stops everything when the window is closed
app/src/main/assets/web/
  index.html, style.css
  js/bridge.js      call/event bridge, jobs, clipboard
  js/editor.js      Monaco models, tabs, autosave, copy/paste
  js/lsp.js         LSP client: diagnostics, completion, hover, definition, rename, code actions...
  js/ui.js          file tree, panels, run/build/test, projects, settings, symbol bar
  js/templates.js   Maven / Gradle / single-file project templates
  js/app.js         entry point
scripts/fetch_assets.py   downloads/stages every embedded binary
```

Two Gradle product flavors (`arm64`, `x64`) differ only in the binaries under `app/src/<flavor>/`. Code comments are mostly in Portuguese.

## Developer notes

- **Debug builds** load the UI from `files/webdev/` when it exists, so you can iterate on HTML/JS without rebuilding the APK: copy `app/src/main/assets/web/` there with `adb` and reload the page.
- Debug builds also expose `window.__cdj` (the JS modules) and `adb shell am start -n dev.codedroid.java/.MainActivity --es js '<javascript>'` to run code in the page, plus `--es hw 0|1` to fake the physical-keyboard check. All of it is ignored in release builds.
- Language-server log: `files/lsp.log` (debug builds can read it with `adb shell run-as`).
- Component versions are pinned at the top of `scripts/fetch_assets.py` and in `Installer.kt`.

## Known limitations

- **Not for the Play Store** (`targetSdk 28`). Distribute the APK directly.
- **No debugger yet** (breakpoints, stepping). It is the biggest missing piece (planned: Java Debug Server + DAP).
- **The terminal runs one command at a time** (no interactive programs); there is no pty yet.
- **Touch editing in Monaco is unverified on real devices** (selection handles, autocorrect/IME quirks).
- Gradle's daemon is stopped after each run to save memory, so each Gradle run starts cold (~12 s on the emulator).
- proot adds overhead to file-heavy work (large dependency downloads and builds are slower than on a PC).
- `git` is installed through `apt` on first run only if there is internet; otherwise the app retries on the next launch.

### Security

- No TCP port is opened: the UI reaches the backend only through the WebView bridge, which accepts messages only from the app's own origin.
- File access is limited to `/root` inside the sandbox and refuses paths that escape it.
- Processes run as *fake* root inside proot; this is **not** real root and does not escape the Android app sandbox.

### Roadmap

Debugger · interactive terminal (pty / xterm.js) · Git panel · Java versions other than 25 · import projects from storage.

## Built with Claude

This project was created end to end by **Claude** (Anthropic, model *Claude Sonnet 5.5*) working in **Claude Code**, in a long conversation with its author, who set the goals, tested on real hardware and reported what broke. Claude validated the idea with a feasibility spike (JDK + language server inside proot), chose the architecture, wrote the Kotlin backend, the whole web UI and the build tooling, and debugged it by reading logs and measuring on an emulator.

It is also a reminder to **review and test AI-written code before you trust it**.

## License

[MIT](LICENSE) for the source code in this repository. The APK bundles third-party software under other licenses (including GPL and EPL components); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

CodeDroid Java is an independent, unofficial project: not affiliated with Oracle, the Eclipse Foundation, Microsoft, Termux, Canonical, the Apache Software Foundation or Gradle Inc.
