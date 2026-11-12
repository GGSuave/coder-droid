package dev.codedroid.java

import android.content.Context
import java.io.File

/** Caminhos e constantes compartilhados. Caminhos "guest" sao os de dentro do Ubuntu (proot). */
class Env(ctx: Context) {
    val home: File = ctx.filesDir
    val rootfs = File(home, "rootfs")
    val tmp = File(home, "tmp")
    val log = File(home, "lsp.log")

    private val nativeDir: String = ctx.applicationInfo.nativeLibraryDir
    val proot = File(nativeDir, "libproot.so")
    val prootLoader = File(nativeDir, "libproot-loader.so")

    /**
     * Converte um caminho de dentro do Ubuntu em arquivo no armazenamento do app.
     * So aceita caminhos dentro de /root (projetos e configuracoes do usuario) e recusa qualquer
     * coisa que, depois de resolver links simbolicos, escape do rootfs.
     */
    fun hostPath(guest: String): File {
        require(guest.startsWith("/")) { "caminho deve ser absoluto: $guest" }
        val normalized = java.nio.file.Paths.get(guest).normalize().toString()
        require(normalized == "/root" || normalized.startsWith("/root/")) { "fora de /root: $guest" }
        val f = File(rootfs, normalized.trimStart('/'))
        val base = File(rootfs, "root").canonicalFile
        val canon = f.canonicalFile
        require(canon == base || canon.path.startsWith(base.path + File.separator)) { "caminho escapa de /root: $guest" }
        return f
    }

    companion object {
        /** Incrementar para refazer o sistema base (o /root do usuario e preservado). */
        const val INSTALL_VERSION = 1
        const val PROJECTS = "/root/projects"
        const val JAVA_HOME = "/opt/jdk"
    }
}
