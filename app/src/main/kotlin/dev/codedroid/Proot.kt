package dev.codedroid

/** Monta comandos que rodam dentro do rootfs (Ubuntu) via proot. */
object Proot {
    private const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Variaveis do lado Android que o proot precisa (libs ao lado do binario, loader, tmp). */
    fun applyEnv(pb: ProcessBuilder, env: Env): ProcessBuilder {
        pb.environment().apply {
            remove("LD_PRELOAD")
            put("LD_LIBRARY_PATH", env.proot.parent ?: "")
            put("PROOT_LOADER", env.prootLoader.path)
            put("PROOT_TMP_DIR", env.tmp.path)
        }
        return pb
    }

    /**
     * @param inner  comando a executar dentro do rootfs
     * @param extraEnv variaveis extras (formato "NOME=valor") para o ambiente do comando
     */
    fun builder(env: Env, inner: List<String>, extraEnv: List<String> = emptyList()): ProcessBuilder {
        val cmd = listOf(
            env.proot.path,
            "-0", "--link2symlink", "--kill-on-exit",
            "-r", env.rootfs.path,
            "-w", "/root",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp",
            "PATH=$PATH",
        ) + extraEnv + inner
        return applyEnv(ProcessBuilder(cmd).directory(env.home).redirectErrorStream(true), env)
    }
}
