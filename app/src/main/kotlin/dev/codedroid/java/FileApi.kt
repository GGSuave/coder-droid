package dev.codedroid.java

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Operacoes de arquivo da IDE. Todos os caminhos sao "guest" (dentro do Ubuntu) e limitados a /root. */
class FileApi(private val env: Env) {

    companion object {
        private const val MAX_READ = 6L * 1024 * 1024
    }

    fun list(path: String): JSONArray {
        val dir = env.hostPath(path)
        require(dir.isDirectory) { "nao e uma pasta: $path" }
        val entries = (dir.listFiles() ?: emptyArray()).sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
        )
        val out = JSONArray()
        for (f in entries) {
            out.put(
                JSONObject()
                    .put("name", f.name)
                    .put("dir", f.isDirectory)
                    .put("link", Files.isSymbolicLink(f.toPath()))
                    .put("size", if (f.isFile) f.length() else 0),
            )
        }
        return out
    }

    fun read(path: String): String {
        val f = env.hostPath(path)
        require(f.isFile) { "arquivo nao encontrado: $path" }
        require(f.length() <= MAX_READ) { "arquivo grande demais (${f.length() / 1024} KB)" }
        val bytes = f.readBytes()
        val head = minOf(bytes.size, 8000)
        for (i in 0 until head) require(bytes[i].toInt() != 0) { "arquivo binario" }
        return String(bytes, Charsets.UTF_8)
    }

    fun write(path: String, text: String) {
        val f = env.hostPath(path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.cdtmp")
        tmp.writeBytes(text.toByteArray(Charsets.UTF_8))
        if (!tmp.renameTo(f)) { // renameTo pode falhar se o destino existe em alguns sistemas
            f.delete()
            require(tmp.renameTo(f)) { "nao foi possivel salvar $path" }
        }
    }

    fun mkdirs(path: String) {
        require(env.hostPath(path).mkdirs() || env.hostPath(path).isDirectory) { "nao foi possivel criar a pasta" }
    }

    fun exists(path: String): Boolean = env.hostPath(path).exists()

    fun rename(from: String, to: String) {
        val src = env.hostPath(from)
        val dst = env.hostPath(to)
        require(src.exists()) { "origem nao existe" }
        require(!dst.exists()) { "ja existe: ${dst.name}" }
        dst.parentFile?.mkdirs()
        require(src.renameTo(dst)) { "nao foi possivel renomear" }
    }

    fun delete(path: String) {
        val f = env.hostPath(path)
        require(f.path != File(env.rootfs, "root").path) { "nao e permitido apagar /root" }
        deleteSafe(f)
    }

    /** Apaga sem seguir links simbolicos (so o link e removido, nunca o alvo). */
    private fun deleteSafe(f: File) {
        if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory) {
            f.listFiles()?.forEach { deleteSafe(it) }
        }
        f.delete()
    }
}
