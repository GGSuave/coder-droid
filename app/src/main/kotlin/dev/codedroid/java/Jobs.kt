package dev.codedroid.java

import android.os.Build
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Roda comandos (mvn, gradle, java, bash...) dentro do rootfs e transmite a saida em tempo real. */
class JobManager(private val env: Env, private val emit: (event: String, data: org.json.JSONObject) -> Unit) {

    private class Job(val process: Process, val marker: String)

    private val jobs = ConcurrentHashMap<Int, Job>()
    private val seq = AtomicInteger(1)

    fun run(command: String, cwd: String): Int {
        val id = seq.getAndIncrement()
        val marker = "CD_JOB=$id"
        // bash -c (sem -l): o /etc/profile do Ubuntu reescreveria o PATH e perderia o JDK.
        val p = Proot.builder(env, listOf("/bin/bash", "-c", command), listOf(marker), cwd).start()
        jobs[id] = Job(p, marker)

        thread(name = "job-$id-out", isDaemon = true) {
            val reader = InputStreamReader(p.inputStream, Charsets.UTF_8)
            val buf = CharArray(4096)
            while (true) {
                val n = try { reader.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                emit("proc.out", org.json.JSONObject().put("id", id).put("text", String(buf, 0, n)))
            }
        }
        thread(name = "job-$id-wait", isDaemon = true) {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            Tools.killByMarker(marker) // sobras (ex.: daemon que ficou preso ao terminal)
            jobs.remove(id)
            emit("proc.exit", org.json.JSONObject().put("id", id).put("code", code))
        }
        return id
    }

    fun stdin(id: Int, text: String) {
        val job = jobs[id] ?: return
        runCatching { job.process.outputStream.apply { write(text.toByteArray(Charsets.UTF_8)); flush() } }
    }

    fun kill(id: Int) {
        val job = jobs[id] ?: return
        Tools.killByMarker(job.marker)
        job.process.destroyForcibly()
    }

    fun killAll() {
        jobs.keys.toList().forEach { kill(it) }
    }
}

/** Servidor de linguagem Java (Eclipse JDT LS) em stdio, dentro do rootfs. */
class LspSession(
    private val env: Env,
    private val onMessage: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private var process: Process? = null
    private val marker = "CD_LSP=1"
    private val writeLock = Any()

    val running get() = process?.isAlive == true

    fun start(root: String) {
        stop()
        val jdtls = File(env.rootfs, "opt/jdtls")
        val launcher = File(jdtls, "plugins").listFiles { f ->
            f.name.startsWith("org.eclipse.equinox.launcher_") && f.name.endsWith(".jar")
        }?.firstOrNull() ?: error("JDT LS nao encontrado (instalacao incompleta)")

        // A configuracao do Equinox e gravavel e guarda cache; usa uma copia no /root.
        val arch = if (Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") "config_linux_arm" else "config_linux"
        val cfgGuest = "/root/.cache/jdtls/config-$arch-${launcher.name.hashCode().toUInt()}"
        val cfgHost = File(env.rootfs, cfgGuest.trimStart('/'))
        if (!cfgHost.exists()) {
            File(env.rootfs, "root/.cache/jdtls").listFiles { f -> f.name.startsWith("config-") }?.forEach { it.deleteRecursively() }
            File(jdtls, arch).copyRecursively(cfgHost, overwrite = true)
        }
        val ws = "/root/.cache/jdtls/ws-" + root.hashCode().toUInt().toString(16)

        val args = listOf(
            "${Env.JAVA_HOME}/bin/java",
            "-Declipse.application=org.eclipse.jdt.ls.core.id1",
            "-Dosgi.bundles.defaultStartLevel=4",
            "-Declipse.product=org.eclipse.jdt.ls.core.product",
            "-Dlog.level=WARNING",
            "-Xmx1G", "-XX:+UseSerialGC", "-Xshare:auto",
            "--add-modules=ALL-SYSTEM",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "-jar", "/opt/jdtls/plugins/${launcher.name}",
            "-configuration", cfgGuest,
            "-data", ws,
        )
        val p = Proot.builder(env, args, listOf(marker), root, mergeStderr = false).start()
        process = p

        thread(name = "lsp-err", isDaemon = true) {
            runCatching { p.errorStream.copyTo(env.log.outputStream()) }
        }
        thread(name = "lsp-out", isDaemon = true) {
            val input = BufferedInputStream(p.inputStream)
            try {
                while (true) {
                    val body = readFrame(input) ?: break
                    onMessage(body)
                }
            } catch (_: Exception) {
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (process === p) process = null
            onExit(code)
        }
    }

    private fun readFrame(input: InputStream): String? {
        var length = -1
        val line = ByteArrayOutputStream()
        while (true) { // cabecalhos ate a linha vazia
            line.reset()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
            }
            val s = line.toString(Charsets.US_ASCII.name())
            if (s.isEmpty()) break
            if (s.startsWith("Content-Length:", ignoreCase = true)) length = s.substringAfter(':').trim().toInt()
        }
        if (length < 0) return null
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(body, off, length - off)
            if (n < 0) return null
            off += n
        }
        return String(body, Charsets.UTF_8)
    }

    fun send(json: String) {
        val p = process ?: return
        val bytes = json.toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            runCatching {
                p.outputStream.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                p.outputStream.write(bytes)
                p.outputStream.flush()
            }
        }
    }

    fun stop() {
        process?.let {
            Tools.killByMarker(marker)
            it.destroyForcibly()
        }
        process = null
    }
}
