package dev.codedroid.java

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
 * Prepara o ambiente na primeira abertura (e repara/atualiza nas seguintes), tudo a partir de
 * arquivos embutidos no APK:
 *  - sistema base (Ubuntu) em filesDir/rootfs;
 *  - componentes versionados em /opt: JDK, Maven, Gradle e o servidor de linguagem Java (JDT LS).
 * Cada componente tem o proprio marcador de versao, entao atualizar um deles nao refaz o resto.
 */
object Installer {

    private class Component(
        val name: String, val version: String, val asset: String,
        val dir: String, val strip: Int, val label: String, val percent: Int,
    )

    // percent = fatia da barra de progresso (somadas com a base e a configuracao = 100)
    private const val BASE_PERCENT = 12
    private val COMPONENTS = listOf(
        Component("jdk", "25.0.5", "jdk.bin", "opt/jdk", 1, "Extraindo JDK 25", 43),
        Component("maven", "3.9.16", "maven.bin", "opt/maven", 1, "Extraindo Maven", 3),
        Component("gradle", "9.9.1", "gradle.bin", "opt/gradle", 1, "Extraindo Gradle", 27),
        Component("jdtls", "1.64.0", "jdtls.bin", "opt/jdtls", 0, "Extraindo servidor de linguagem Java", 10),
    )

    private fun marker(env: Env, c: Component) = File(env.rootfs, "opt/.installed/${c.name}-${c.version}")
    private fun baseMarker(env: Env) = File(env.rootfs, ".installed-v${Env.INSTALL_VERSION}")

    private fun baseInstalled(env: Env) = baseMarker(env).exists() && File(env.rootfs, "bin/sh").exists()

    private fun componentInstalled(env: Env, c: Component) =
        marker(env, c).exists() && File(env.rootfs, c.dir).isDirectory

    /** True quando ha algo a instalar (usado para mostrar a tela de progresso). */
    fun needsWork(env: Env) = !baseInstalled(env) || COMPONENTS.any { !componentInstalled(env, it) }

    fun ensureAll(ctx: Context, env: Env, onProgress: (message: String, percent: Int?) -> Unit) {
        var done = 0
        if (!baseInstalled(env)) {
            installBase(ctx, env) { m, p -> onProgress(m, p * BASE_PERCENT / 100) }
        }
        done += BASE_PERCENT

        for (c in COMPONENTS) {
            if (!componentInstalled(env, c)) {
                val start = done
                File(env.rootfs, c.dir).deleteRecursively()
                File(env.rootfs, "opt/.installed").listFiles { f -> f.name.startsWith(c.name + "-") }?.forEach { it.delete() }
                extract(ctx, c.asset, File(env.rootfs, c.dir), c.strip, c.label) { m, p ->
                    onProgress(m, start + p * c.percent / 100)
                }
                marker(env, c).apply { parentFile?.mkdirs(); writeText("ok") }
            }
            done += c.percent
        }

        onProgress("Configurando ambiente…", done)
        configure(ctx, env)
        runCatching { ProcessBuilder("/system/bin/sync").start().waitFor() }

        ensureGit(env, onProgress)
        onProgress("Pronto", 100)
    }

    // ------------------------------------------------------------ base

    private fun installBase(ctx: Context, env: Env, onProgress: (String, Int) -> Unit) {
        baseMarker(env).delete()
        onProgress("Preparando…", 0)

        // O /root do usuario (projetos, configuracoes) sobrevive a reinstalacoes do sistema base.
        val keep = File(env.home, "root-keep")
        keep.deleteRecursively()
        val oldRoot = File(env.rootfs, "root")
        if (oldRoot.exists()) oldRoot.renameTo(keep)

        env.rootfs.deleteRecursively()
        env.rootfs.mkdirs()
        extract(ctx, "rootfs.bin", env.rootfs, 0, "Extraindo sistema base") { m, p -> onProgress(m, 2 + p * 98 / 100) }

        if (keep.exists()) {
            File(env.rootfs, "root").deleteRecursively()
            keep.renameTo(File(env.rootfs, "root"))
        }
        baseMarker(env).writeText("ok")
    }

    // ------------------------------------------------------------ tar.gz

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    private fun extract(
        ctx: Context, asset: String, dest: File, strip: Int, label: String,
        onProgress: (String, Int) -> Unit,
    ) {
        dest.mkdirs()
        val destPath = dest.toPath().normalize()
        ctx.assets.openFd(asset).use { fd ->
            val total = fd.length.coerceAtLeast(1)
            val counting = Counting(fd.createInputStream())
            val tar = TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(counting, 1 shl 16)))
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
                            out.delete()
                            File(dest, linkTarget).copyTo(out, overwrite = true)
                            chmod(out, e.mode, dir = false)
                        }
                        e.isFile -> {
                            out.parentFile?.mkdirs()
                            if (out.exists()) out.delete()
                            out.outputStream().buffered(1 shl 16).use { os -> tar.copyTo(os) }
                            chmod(out, e.mode, dir = false)
                        }
                    }

                    val pct = (counting.count * 100 / total).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress("$label… $pct%", pct)
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

    // ------------------------------------------------------------ config

    private fun configure(ctx: Context, env: Env) {
        fun write(rel: String, text: String, mode: Int = 0x1A4 /* 0644 */, overwrite: Boolean = true) {
            val f = File(env.rootfs, rel)
            if (!overwrite && f.exists()) return
            f.parentFile?.mkdirs()
            runCatching { Os.remove(f.path) }
            f.writeText(text)
            Os.chmod(f.path, mode)
        }

        write("etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        write("etc/hosts", "127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        // apt tenta largar privilegios para o usuario _apt, o que falha sob proot.
        write("etc/apt/apt.conf.d/99codedroid", "APT::Sandbox::User \"root\";\n")

        // Certificados raiz (o Ubuntu Base minimo nao traz): git e ferramentas de rede precisam.
        val ca = File(env.rootfs, "etc/ssl/certs/ca-certificates.crt")
        if (!ca.exists() || ca.length() == 0L) {
            ca.parentFile?.mkdirs()
            ctx.assets.open("ca-certificates.crt").use { i -> ca.outputStream().use { o -> i.copyTo(o) } }
            runCatching { Os.chmod(ca.path, 0x1A4) }
        }

        // Git guarda a credencial digitada (um token, por exemplo) e reutiliza.
        write("etc/gitconfig", "[credential]\n\thelper = store\n[safe]\n\tdirectory = *\n", overwrite = false)

        // Gradle: daemon com pouca memoria e sem monitor de arquivos (inotify sob proot e instavel).
        write(
            "root/.gradle/gradle.properties",
            "org.gradle.jvmargs=-Xmx768m -XX:+UseSerialGC -Dfile.encoding=UTF-8\n" +
                "org.gradle.vfs.watch=false\norg.gradle.parallel=false\norg.gradle.daemon.idletimeout=900000\n",
            overwrite = false,
        )

        write(
            "root/.bashrc",
            """
            export LANG=C.UTF-8
            export JAVA_HOME=/opt/jdk
            export PATH=/opt/jdk/bin:/opt/maven/bin:/opt/gradle/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            alias ls='ls --color=auto'
            PS1='\[\e[32m\]\u@codedroid-java\[\e[0m\]:\[\e[34m\]\w\[\e[0m\]\$ '
            """.trimIndent() + "\n",
            overwrite = false,
        )
        write("root/.hushlogin", "", overwrite = false)

        File(env.rootfs, "root/projects").mkdirs()
        File(env.rootfs, "tmp").apply { mkdirs(); Os.chmod(path, 0x3FF /* 1777 */) }
        env.tmp.mkdirs()
    }

    // ------------------------------------------------------------ git (apt)

    /** git nao vem no Ubuntu Base. Instala uma vez, se houver internet; senao tenta na proxima abertura. */
    private fun ensureGit(env: Env, onProgress: (String, Int?) -> Unit) {
        if (File(env.rootfs, "usr/bin/git").exists()) return
        val online = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("archive.ubuntu.com", 80), 3000); true }
        }.getOrDefault(false)
        if (!online) return

        onProgress("Instalando git (primeira vez, precisa de internet)…", null)
        val pb = Proot.builder(
            env,
            listOf("/bin/bash", "-c", "apt-get update -qq && apt-get install -y -qq --no-install-recommends git ca-certificates"),
            listOf("DEBIAN_FRONTEND=noninteractive"),
        ).redirectOutput(File(env.home, "git-install.log"))
        runCatching {
            val p = pb.start()
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.MINUTES)) p.destroyForcibly()
        }
    }
}
