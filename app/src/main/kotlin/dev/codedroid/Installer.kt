package dev.codedroid

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Primeira execucao: descompacta o Ubuntu base e o code-server (embutidos em assets/)
 * dentro de filesDir/rootfs e escreve a configuracao minima para rodar sob proot.
 */
object Installer {

    /** Marcador + arquivos essenciais: uma instalacao interrompida se refaz sozinha. */
    fun isInstalled(env: Env) = env.marker.exists() && listOf(
        "opt/code-server/bin/code-server",
        "opt/code-server/lib/node",
        "usr/local/bin/node",
        "bin/sh",
    ).all { File(env.rootfs, it).exists() }

    fun install(ctx: Context, env: Env, onProgress: (message: String, percent: Int) -> Unit) {
        env.marker.delete()
        onProgress("Preparando…", 0)

        // O /root do usuario (projetos, configuracoes) sobrevive a reinstalacoes.
        val keep = File(env.home, "root-keep")
        keep.deleteRecursively()
        val oldRoot = File(env.rootfs, "root")
        if (oldRoot.exists()) oldRoot.renameTo(keep)

        env.rootfs.deleteRecursively()
        env.rootfs.mkdirs()

        extract(ctx, "rootfs.bin", env.rootfs, strip = 0, label = "Extraindo sistema base", base = 1, span = 9, onProgress)
        extract(
            ctx, "code-server.bin", File(env.rootfs, "opt/code-server"),
            strip = 1, label = "Extraindo VS Code", base = 10, span = 70, onProgress,
        )
        extract(
            ctx, "node.bin", File(env.rootfs, "usr/local"),
            strip = 1, label = "Extraindo Node.js", base = 80, span = 18, onProgress,
        )

        if (keep.exists()) {
            File(env.rootfs, "root").deleteRecursively()
            keep.renameTo(File(env.rootfs, "root"))
        }

        onProgress("Configurando ambiente…", 99)
        configure(ctx, env)
        // Forca a gravacao em disco antes de declarar sucesso (queda de bateria/morte do app).
        runCatching { ProcessBuilder("/system/bin/sync").start().waitFor() }
        env.marker.writeText("ok")
        onProgress("Pronto", 100)
    }

    // ---------------------------------------------------------------- tar.gz

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    private fun extract(
        ctx: Context, asset: String, dest: File, strip: Int,
        label: String, base: Int, span: Int,
        onProgress: (String, Int) -> Unit,
    ) {
        dest.mkdirs()
        val destPath = dest.toPath().normalize()
        ctx.assets.openFd(asset).use { fd ->
            val total = fd.length.coerceAtLeast(1)
            val counting = Counting(fd.createInputStream())
            val tar = TarArchiveInputStream(
                GzipCompressorInputStream(BufferedInputStream(counting, 1 shl 16)),
            )
            var lastPct = -1
            tar.use {
                while (true) {
                    val e: TarArchiveEntry = tar.nextEntry ?: break
                    var name = e.name.removePrefix("./")
                    repeat(strip) { name = name.substringAfter('/', "") }
                    name = name.trimEnd('/')
                    if (name.isEmpty()) continue

                    val out = File(dest, name)
                    if (!out.toPath().normalize().startsWith(destPath)) continue // path traversal

                    when {
                        e.isDirectory -> {
                            out.mkdirs()
                            chmod(out, e.mode, dir = true)
                        }
                        e.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            runCatching { Os.remove(out.path) }
                            Os.symlink(e.linkName, out.path)
                        }
                        e.isLink -> {
                            out.parentFile?.mkdirs()
                            var linkTarget = e.linkName.removePrefix("./")
                            repeat(strip) { linkTarget = linkTarget.substringAfter('/', "") }
                            val src = File(dest, linkTarget)
                            out.delete()
                            src.copyTo(out, overwrite = true)
                            chmod(out, e.mode, dir = false)
                        }
                        e.isFile -> {
                            out.parentFile?.mkdirs()
                            if (out.exists()) out.delete()
                            out.outputStream().buffered(1 shl 16).use { os -> tar.copyTo(os) }
                            chmod(out, e.mode, dir = false)
                        }
                        // dispositivos, fifos etc. sao ignorados
                    }

                    val pct = (counting.count * 100 / total).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress("$label… $pct%", base + pct * span / 100)
                    }
                }
            }
        }
    }

    /** Garante rw para o dono (o proot simula root) e remove setuid/setgid. */
    private fun chmod(f: File, mode: Int, dir: Boolean) {
        val m = (mode and 0x1FF) or (if (dir) 0x1C0 else 0x180)
        runCatching { Os.chmod(f.path, m) }
    }

    // ---------------------------------------------------------------- config

    // Configuracoes de Git/GitHub: o VS Code injeta o proprio GIT_ASKPASS nos terminais, o que
    // faz `git pull` abrir um modal em vez de perguntar no terminal. Desligado por padrao.
    private val GIT_SETTINGS = mapOf<String, Any>(
        "git.terminalAuthentication" to false,
        "github.gitAuthentication" to false,
    )

    /**
     * Componentes acrescentados depois da instalacao base. Sao aplicados sem refazer o rootfs,
     * para nao apagar o que o usuario instalou com apt.
     */
    fun ensureExtras(ctx: Context, env: Env, onProgress: (message: String, percent: Int?) -> Unit) {
        // 1. Certificados raiz (o Ubuntu Base minimo nao traz; gh e git precisam para HTTPS)
        val ca = File(env.rootfs, "etc/ssl/certs/ca-certificates.crt")
        if (!ca.exists() || ca.length() == 0L) {
            ca.parentFile?.mkdirs()
            ctx.assets.open("ca-certificates.crt").use { i -> ca.outputStream().use { o -> i.copyTo(o) } }
            runCatching { Os.chmod(ca.path, 0x1A4) }
        }

        // 2. GitHub CLI (login por codigo dentro do terminal)
        if (!File(env.rootfs, "usr/local/bin/gh").exists()) {
            extract(ctx, "gh.bin", File(env.rootfs, "usr/local"), strip = 1, label = "Instalando GitHub CLI", base = 0, span = 100) { m, p -> onProgress(m, p) }
        }

        // 3. Ajustes do VS Code para Git (acrescenta so as chaves que faltam)
        val settings = File(env.rootfs, "root/.local/share/code-server/User/settings.json")
        if (settings.exists()) GIT_SETTINGS.forEach { (k, v) -> ensureSetting(settings, k, v) }

        // 4. Extensao embutida "GitHub": pede login com `createIfNone` (modal "conectar ao GitHub") ao
        //    clonar/publicar/listar PRs e como provedor de credenciais do git. O login aqui e feito
        //    com gh/token no terminal, entao ela e removida (idempotente).
        File(env.rootfs, "opt/code-server/lib/vscode/extensions/github").takeIf { it.exists() }?.deleteRecursively()

        // 5. Git guarda a credencial digitada (store) e tambem aceita a do gh. So se ainda nao existir.
        val gitconfig = File(env.rootfs, "etc/gitconfig")
        if (!gitconfig.exists()) {
            gitconfig.writeText(
                """
                [credential]
                	helper = store
                [credential "https://github.com"]
                	helper = !gh auth git-credential
                [credential "https://gist.github.com"]
                	helper = !gh auth git-credential
                """.trimIndent().replace("\n                ", "\n") + "\n",
            )
            runCatching { Os.chmod(gitconfig.path, 0x1A4) }
        }

        // 6. git (precisa de internet; sem rede, tenta de novo na proxima abertura)
        ensureGit(env, onProgress)
    }

    private fun ensureGit(env: Env, onProgress: (String, Int?) -> Unit) {
        if (File(env.rootfs, "usr/bin/git").exists()) return
        val online = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("archive.ubuntu.com", 80), 3000); true }
        }.getOrDefault(false)
        if (!online) return

        onProgress("Instalando git (primeira vez, precisa de internet)…", null)
        val log = File(env.home, "git-install.log")
        val pb = Proot.builder(
            env,
            listOf("/bin/bash", "-c", "apt-get update -qq && apt-get install -y -qq git ca-certificates"),
            listOf("DEBIAN_FRONTEND=noninteractive"),
        ).redirectOutput(log)
        runCatching {
            val p = pb.start()
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.MINUTES)) p.destroyForcibly()
        }
    }

    /** Define a chave se ainda nao existir. Se o arquivo tiver comentarios (JSONC), nao mexe. */
    private fun ensureSetting(file: File, key: String, value: Any) {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val parsed = runCatching { org.json.JSONObject(text) }.getOrNull()
        if (parsed != null) {
            if (!parsed.has(key)) {
                parsed.put(key, value)
                file.writeText(parsed.toString(2) + "\n")
            }
            return
        }
        // JSONC (comentarios/virgula sobrando): insere a chave logo depois da primeira "{".
        if (!text.contains("\"$key\"")) {
            val brace = text.indexOf('{')
            if (brace >= 0) {
                file.writeText(text.substring(0, brace + 1) + "\n  \"$key\": $value," + text.substring(brace + 1))
            }
        }
    }

    private fun configure(ctx: Context, env: Env) {
        // O Node oficial precisa de libatomic.so.1, que o Ubuntu Base minimo nao traz.
        val triplet = if (android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a")
            "aarch64-linux-gnu" else "x86_64-linux-gnu"
        val libDir = File(env.rootfs, "usr/lib/$triplet").apply { mkdirs() }
        ctx.assets.open("libatomic.so.1").use { input ->
            File(libDir, "libatomic.so.1").outputStream().use { input.copyTo(it) }
        }
        Os.chmod(File(libDir, "libatomic.so.1").path, 0x1ED /* 0755 */)

        fun write(rel: String, text: String, mode: Int = 0x1A4 /* 0644 */) {
            val f = File(env.rootfs, rel)
            f.parentFile?.mkdirs()
            runCatching { Os.remove(f.path) }
            f.writeText(text)
            Os.chmod(f.path, mode)
        }

        write("etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        write("etc/hosts", "127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        // apt tenta largar privilegios para o usuario _apt, o que falha sob proot.
        write("etc/apt/apt.conf.d/99codedroid", "APT::Sandbox::User \"root\";\n")

        // O Android bloqueia netlink; o Node quebra em os.networkInterfaces() (usado pelo code-server).
        write(
            "opt/android-shim.js",
            """
            const os = require('os');
            // O Android esconde /proc/cpuinfo: os.cpus() volta vazio e quebra webpack/jest/esbuild.
            const origCpus = os.cpus;
            os.cpus = function () {
              const c = origCpus.call(os);
              if (c && c.length) return c;
              return Array.from({ length: 4 }, () => ({
                model: 'cpu', speed: 0, times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 },
              }));
            };
            const orig = os.networkInterfaces;
            os.networkInterfaces = function () {
              let r;
              try { r = orig.call(os) || {}; } catch (e) { r = {}; }
              if (!r.lo) {
                r.lo = [{ address: '127.0.0.1', netmask: '255.0.0.0', family: 'IPv4',
                          mac: '00:00:00:00:00:00', internal: true, cidr: '127.0.0.1/8' }];
              }
              // O VS Code deriva o machine-id do MAC (rejeita 00:..:00 e interfaces internas).
              const hasReal = Object.values(r).some((l) => (l || []).some(
                (i) => !i.internal && i.mac && i.mac !== '00:00:00:00:00:00'));
              if (!hasReal) {
                r.eth0 = [{ address: '127.0.0.1', netmask: '255.255.255.255', family: 'IPv4',
                            mac: '02:00:00:00:00:01', internal: false, cidr: '127.0.0.1/32' }];
              }
              return r;
            };
            // O code-server e ESM: sem isto, `import { networkInterfaces } from 'os'` ignora o patch.
            try { require('module').syncBuiltinESMExports(); } catch (e) {}
            """.trimIndent() + "\n",
        )

        // Configuracao inicial do VS Code. Nao sobrescreve se o usuario ja tiver a dele.
        val settings = File(env.rootfs, "root/.local/share/code-server/User/settings.json")
        if (!settings.exists()) {
            settings.parentFile?.mkdirs()
            settings.writeText(
                """
                {
                  "chat.disableAIFeatures": true,
                  "workbench.startupEditor": "none",
                  "workbench.tips.enabled": false,
                  "security.workspace.trust.enabled": false,
                  "telemetry.telemetryLevel": "off",
                  "update.mode": "none",
                  "extensions.autoUpdate": false,
                  "files.watcherExclude": { "**/node_modules/**": true, "**/.git/objects/**": true },
                  "terminal.integrated.defaultProfile.linux": "bash",
                  "terminal.integrated.copyOnSelection": true,
                  "git.terminalAuthentication": false,
                  "github.gitAuthentication": false
                }
                """.trimIndent() + "\n",
            )
        } else {
            // Instalacao existente: acrescenta so o que falta, sem tocar no resto (JSON puro apenas).
            ensureSetting(settings, "terminal.integrated.copyOnSelection", true)
        }

        // ~/.bashrc minimo para o terminal integrado.
        write(
            "root/.bashrc",
            """
            export LANG=C.UTF-8
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            alias ls='ls --color=auto'
            PS1='\[\e[32m\]\u@codedroid\[\e[0m\]:\[\e[34m\]\w\[\e[0m\]\$ '
            """.trimIndent() + "\n",
        )

        // Silencia o `groups` do /etc/bash.bashrc (GIDs do Android nao tem nome no rootfs).
        write("root/.hushlogin", "")

        File(env.rootfs, "root/workspace").mkdirs()
        File(env.rootfs, "tmp").apply { mkdirs(); Os.chmod(path, 0x3FF /* 1777 */) }
        env.tmp.mkdirs()
    }
}

