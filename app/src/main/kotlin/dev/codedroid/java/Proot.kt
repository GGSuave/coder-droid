package dev.codedroid.java

/** Monta comandos que rodam dentro do rootfs (Ubuntu) via proot. */
object Proot {
    private const val PATH =
        "/opt/jdk/bin:/opt/maven/bin:/opt/gradle/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

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
     * @param inner     comando a executar dentro do rootfs
     * @param extraEnv  variaveis extras (formato "NOME=valor")
     * @param cwd       diretorio de trabalho dentro do rootfs
     * @param mergeStderr junta stderr no stdout (desligue para protocolos binarios, como o LSP)
     */
    fun builder(
        env: Env,
        inner: List<String>,
        extraEnv: List<String> = emptyList(),
        cwd: String = "/root",
        mergeStderr: Boolean = true,
    ): ProcessBuilder {
        val cmd = listOf(
            env.proot.path,
            "-0", "--link2symlink", "--kill-on-exit",
            "-r", env.rootfs.path,
            "-w", cwd,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "LANG=C.UTF-8", "TERM=dumb", "TMPDIR=/tmp",
            "JAVA_HOME=${Env.JAVA_HOME}",
            "MAVEN_OPTS=-Xmx768m -XX:+UseSerialGC -XX:TieredStopAtLevel=1",
            "GRADLE_USER_HOME=/root/.gradle",
            "PATH=$PATH",
        ) + extraEnv + inner
        return applyEnv(ProcessBuilder(cmd).directory(env.home).redirectErrorStream(mergeStderr), env)
    }
}
