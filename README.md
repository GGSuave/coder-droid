# CodeDroid

**A full VS Code, a Linux terminal, Node.js and Git — running 100% locally inside a single Android APK.**
No server to set up, no Termux session to log into, no browser tab to keep open.

> 🇧🇷 [Leia em português](README.pt-BR.md)
>
> 🤖 **Built with [Claude](https://claude.com/claude-code)** — this whole project was designed, written, debugged and tested by Claude (Anthropic) in [Claude Code](https://claude.com/claude-code), directed by its author through conversation. See [Built with Claude](#built-with-claude).

---

## What is it?

Android phones and tablets are powerful, but their built-in "terminal" cannot install or run real
development tools. CodeDroid bundles a complete Linux userland and the VS Code editor into one app, so you can
open a folder, edit code with extensions, run `npm`, `git` and dev servers, all on the device, offline.

You tap the icon and land in VS Code. That's it.

## Highlights

- **Real VS Code** ([code-server](https://github.com/coder/code-server)): extensions (Open VSX), integrated terminal, Git panel, search, debugger UI.
- **A real Ubuntu 24.04 userland** with `apt`, so `apt install openjdk-21-jdk`, `python3`, `build-essential`… just work.
- **Node.js (LTS) + npm, Git and the GitHub CLI (`gh`)** pre-installed. Verified: `npm create vite` → `npm install` → dev server answering `HTTP 200`.
- **Made for touch and for tablets**: always landscape (both sides, like a game), a key bar (Copy, Paste, Esc, Tab, Ctrl-C, arrows…) that only shows with the on-screen keyboard and no physical keyboard, working copy/paste.
- **Links open where you expect**: a `localhost:3000` link clicked in the terminal opens in **Chrome**, not inside the editor.
- **Remembers your folder** and reopens it next time.
- **Behaves like a good Android app**: closing the window in the app switcher stops the server, every terminal and every dev server. The notification has a *Stop* button. A themed (Android 13+) adaptive icon.
- **Resilient**: if Android kills the WebView's renderer (common when you switch to the browser), the app rebuilds the page and reconnects to your still-running terminals instead of crashing.
- **Self-contained**: the editor, Node, `gh` and CA certificates are inside the APK. Only `git` (via `apt`) needs internet, once, on first run.

## How it works

```
┌──────────────────────────── Android app  (dev.codedroid) ────────────────────────────┐
│                                                                                       │
│  MainActivity ── WebView ───────────────▶  http://127.0.0.1:18080   (the VS Code UI)  │
│     ├ ClipboardBridge   (navigator.clipboard ⇄ Android clipboard, origin-restricted)  │
│     ├ link router       (localhost / external links → Chrome)                         │
│     └ key bar, orientation, last-folder memory, renderer-crash recovery               │
│                                                                                       │
│  ServerService  (foreground service, wake lock, notification)                         │
│     └─ spawns ▶  libproot.so            ← exec'd from nativeLibraryDir (see below)     │
│                    └─ rootfs/   (Ubuntu 24.04, lives in the app's private files dir)  │
│                         ├ /opt/code-server/     VS Code server (HTTP, no auth, local)  │
│                         ├ /usr/local/bin/       node  npm  npx  gh                     │
│                         ├ /usr/bin/git          installed with apt on first run        │
│                         └ /root/workspace       your projects  (kept across updates)  │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

**First launch** (`Installer.kt`)
1. Unpacks the bundled Ubuntu base, code-server and Node.js tarballs into the app's private storage (no root needed).
2. Writes the small bits of config the sandbox needs (`resolv.conf`, `hosts`, a Node shim, VS Code defaults).
3. Adds extras: CA certificates, GitHub CLI, Git credential config, and installs `git` through `apt` if there is internet.

**Every launch**
1. `ServerService` starts `libproot.so -r rootfs … code-server --bind-addr 127.0.0.1:18080 --auth none` as a child process.
2. `MainActivity` polls `/healthz`, then loads the editor in a `WebView`.

**When you close the window**, `onTaskRemoved` stops the server and kills the whole process tree.

### The Android problems this project had to solve

| Problem | What happens | Solution |
|---|---|---|
| **W^X**: Android 10+ forbids executing files from an app's data dir for apps with `targetSdk ≥ 29` | `Permission denied` on any binary you unpack | Ship `proot` as `lib*.so` so it is installed in `nativeLibraryDir` (the one place apps may exec from), and keep `targetSdk = 28` like Termux does. **Consequence: not eligible for the Play Store.** |
| No root, no `chroot` | Can't run a normal Linux userland | **proot** (user-space `chroot` via `ptrace`), the Termux build, which works under Android's seccomp policy. |
| proot is linked to `libtalloc.so.2`, but `jniLibs` only accepts `lib*.so` | Linker can't find the dependency | `scripts/fetch_assets.py` renames the `DT_NEEDED` string in place (same length). |
| Android hides `/proc/cpuinfo` and blocks netlink | `os.cpus()` is empty, `os.networkInterfaces()` throws; webpack/jest/esbuild and VS Code break | `/opt/android-shim.js`, loaded with `NODE_OPTIONS`, patches `os` and calls `syncBuiltinESMExports()` so ES modules see it too. |
| WebView can't ask permission for `navigator.clipboard.readText()` | "Paste" from VS Code menus does nothing | `ClipboardBridge`: a `WebMessageListener` + document-start script replace `navigator.clipboard`, **restricted to the editor's origin** so sandboxed extension iframes can't read your clipboard. |
| VS Code rewrites `localhost:3000` → `/proxy/3000/` on its own server | Clicking a dev-server link navigates the editor away | Main-frame navigations to `/proxy/<port>/…` are turned back into `http://localhost:<port>/…` and opened in Chrome. |
| Android kills the WebView renderer under memory pressure; unhandled, **it kills the whole app** | Server and terminals die while you're in the browser | `onRenderProcessGone` rebuilds the WebView on the same URL; the server is untouched. |
| VS Code's built-in "GitHub" extension pops a *"sign in to GitHub"* modal (`createIfNone: true`) | Login modal after clone / pull | The extension is removed; login is `gh auth login` or a token in the terminal. Git is preconfigured to store credentials. |
| `Process.destroy()` didn't reliably stop proot's children | Orphaned `node` processes after closing | After stopping, the service walks `/proc` and `SIGKILL`s every descendant. |
| A half-finished first install (battery died, app killed) | App starts with a broken rootfs | `sync` before marking success, and on every launch essential files are verified; a broken install redoes itself. |

## Requirements

| | |
|---|---|
| Device | Android **8.0+** (API 26). **arm64** phones/tablets use the `arm64` flavor; the `x64` flavor is for emulators / x86_64 devices. |
| Tested on | Android 15 (x86_64 emulator) and a real arm64 phone. Other versions/devices are untested. |
| Space | ~330 MB for the APK and about **1.5 GB** free once it unpacks. |
| RAM | 6 GB or more recommended. VS Code + Node dev servers are not light. |
| Build host | JDK 17+ (the JDK bundled with Android Studio works), Android SDK (platform **36**, build-tools), **Python 3.14+** (or 3.9+ with `pip install zstandard`), internet. |

## Build from source

The repository holds only the source (~120 KB). The binaries that go into the APK (Ubuntu, code-server, Node.js, `gh`, proot…) are downloaded by a script.

```bash
git clone https://github.com/<you>/codedroid.git
cd codedroid

# 1. download everything the APK embeds (≈ 300 MB per architecture)
python scripts/fetch_assets.py --abi arm64      # phones/tablets  (or: --abi x64 / --abi all)

# 2. build a debug APK (signed with the debug key; fine for personal use)
./gradlew :app:assembleArm64Debug               # Windows: gradlew.bat ...

# 3. install
adb install app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

Point Gradle at your SDK with `ANDROID_HOME`, or a `local.properties` file containing `sdk.dir=/path/to/Android/sdk`.

### A signed release

```bash
cp keystore.properties.example keystore.properties   # then edit it (it is git-ignored)
keytool -genkeypair -keystore codedroid-release.jks -alias codedroid -keyalg RSA -keysize 4096 -validity 10000
./gradlew :app:assembleArm64Release
# → app/build/outputs/apk/arm64/release/app-arm64-release.apk
```

> ⚠️ Keep the `.jks` and its passwords **outside** the repository and back them up. If you lose the key you cannot ship updates that install over an existing install.
> Without `keystore.properties` the release build is produced unsigned.

## Using it

- **First open** takes about a minute (unpacking + installing `git`). After that it opens in a few seconds.
- Android asks for **notification permission** (allow it, or you lose the *Stop* button) and offers a **battery optimisation exemption** (accept it, otherwise the system may stop the server in the background).
- **Terminal:** the panel button in the top-right, or `` Ctrl+` ``. Tap-select text and it is copied automatically.
- **GitHub login:** in the terminal run `gh auth login` → *GitHub.com* → *HTTPS* → *Yes* (authenticate Git) → *Login with a web browser*. Select the one-time code, press Enter, Chrome opens, paste, authorise, come back. After that `git pull`/`push` need no password. A personal access token also works: Git asks once and remembers it.
- **More tools:** it's Ubuntu: `apt update && apt install openjdk-21-jdk python3 build-essential`.
- **Close the app** from the app switcher to stop everything. The Back button only sends it to the background and leaves the server running.
- **Updating** the APK (same signing key) keeps your `/root` (projects and settings). Things you installed with `apt` live in the system image; they are lost if a release has to rebuild the base system (`INSTALL_VERSION` in `Env.kt` changes). Back up what matters.

## Project layout

```
app/src/main/kotlin/dev/codedroid/
  MainActivity.kt      WebView host, key bar, link routing, crash recovery, last folder
  ServerService.kt     foreground service: starts/stops the server and its whole process tree
  Installer.kt         first-run unpacking, config, extras (gh, certs, git), settings merge
  Proot.kt             builds the proot command line shared by the server and the installer
  ClipboardBridge.kt   navigator.clipboard ⇄ Android clipboard, restricted by origin
  Env.kt               paths and constants (port 18080, INSTALL_VERSION…)
app/src/main/res/      adaptive + monochrome launcher icon, notification icon, theme
scripts/fetch_assets.py   downloads/stages every embedded binary
```

Two Gradle product flavors (`arm64`, `x64`) differ only in the binaries under `app/src/<flavor>/`. Code comments are mostly in Portuguese.

## Developer notes

- Logs: `adb logcat -s codedroid-server codedroid-web` (server output, lifecycle, renderer recovery). This works in release builds too.
- **Debug builds only** expose test hooks: `adb shell am start -n dev.codedroid/.MainActivity --es js '<javascript>'`, `--es cb_set <text>`, `--ez cb_get true`, `--es hw 0|1` (fake the physical-keyboard check), and a `files/launch.sh` that replaces the server command. They are ignored in release builds.
- The code-server version, Node.js version and so on are pinned at the top of `scripts/fetch_assets.py`.

## Troubleshooting

- **The server dies in the background / terminals vanish.** Android 12+ may limit "phantom" child processes of apps. Accept the battery-optimisation exemption, and if it persists enable *Developer options → Disable child process restrictions* (Android 14+) or, over ADB, `adb shell settings put global settings_enable_monitor_phantom_procs false` (exact commands vary by Android version).
- **White screen / "WebView failed repeatedly".** Update *Android System WebView* and *Chrome* in the Play Store.
- **Error on first launch.** Free some storage and reopen; an interrupted install repairs itself.
- Anything else: open an issue with the output of `adb logcat -s codedroid-server`.

## Known limitations & security

- **Not for the Play Store** (`targetSdk 28`, see above). Distribute the APK directly.
- **The editor has no password.** code-server listens on `127.0.0.1:18080` with `--auth none`. Nothing outside the device can reach that address, but **any other app installed on the device can**, and the editor includes a terminal inside the sandbox with your projects and any stored Git token. Don't install untrusted apps next to this one. A per-install random password injected into the WebView is the obvious fix and is on the roadmap.
- Processes in the sandbox run as *fake* root inside proot; this is **not** real root and does not escape the Android app sandbox.
- proot adds overhead to file-heavy work (`npm install` of large trees is noticeably slower than on a PC).
- Performance numbers quoted above were measured on an x86_64 emulator on a desktop PC; real devices will differ.
- The Microsoft Marketplace is not available (code-server uses Open VSX); some proprietary extensions won't install.

### Roadmap ideas

Per-install authentication · optional Java pre-install · storage access (`/sdcard`) · multiple workspaces · CI that builds a release from tags.

## Built with Claude

This project was created end to end by **Claude** (Anthropic, model *Claude Sonnet 5.5*) working in **Claude Code**, in a long conversation with its author who set the goals, tested on real hardware and reported what broke. Claude chose the architecture, wrote the Kotlin app and the tooling, set up the Android SDK and emulator, and debugged it by reading logs and measuring on the emulator.

It is a good example of what AI-assisted development can do, and also a reminder to **review and test AI-written code before you trust it**, especially the security notes above.

## License

[MIT](LICENSE) for the source code in this repository. The APK bundles third-party software under other licenses (including GPL components); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

CodeDroid is an independent, unofficial project: not affiliated with Microsoft, Coder, Termux, Canonical, GitHub or the Node.js project.
