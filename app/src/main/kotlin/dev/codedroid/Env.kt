package dev.codedroid

import android.content.Context
import java.io.File

/** Caminhos e constantes compartilhados entre instalador, servico e UI. */
class Env(ctx: Context) {
    val home: File = ctx.filesDir
    val rootfs = File(home, "rootfs")
    val tmp = File(home, "tmp")
    val log = File(home, "server.log")
    val marker = File(home, ".installed-v$INSTALL_VERSION")

    private val nativeDir: String = ctx.applicationInfo.nativeLibraryDir
    val proot = File(nativeDir, "libproot.so")
    val prootLoader = File(nativeDir, "libproot-loader.so")

    val url get() = "http://127.0.0.1:$PORT"

    companion object {
        /** Incrementar para forcar reinstalacao do rootfs em atualizacoes do app. */
        const val INSTALL_VERSION = 6
        const val PORT = 18080
        const val WORKSPACE = "/root/workspace"
    }
}
