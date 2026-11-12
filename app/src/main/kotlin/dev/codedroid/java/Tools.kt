package dev.codedroid.java

import java.io.File

/** Utilitarios de processo. O proot nao encerra os filhos de forma confiavel com Process.destroy(). */
object Tools {

    private fun readIso(f: File): String =
        runCatching { f.readBytes().toString(Charsets.ISO_8859_1) }.getOrDefault("")

    /**
     * PIDs (do mesmo usuario) cujo cmdline ou ambiente contem `marker` como argumento/variavel
     * completa. Cada trabalho recebe um marcador unico (CD_JOB=n), herdado por todos os filhos.
     */
    fun pidsWithMarker(marker: String): List<Int> {
        val me = android.os.Process.myPid()
        val needle = marker + "\u0000"
        val out = mutableListOf<Int>()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            if (readIso(File(d, "cmdline")).contains(needle) || readIso(File(d, "environ")).contains(needle)) {
                out += pid
            }
        }
        return out
    }

    fun killByMarker(marker: String): Int {
        val pids = pidsWithMarker(marker)
        pids.forEach { android.os.Process.killProcess(it) }
        return pids.size
    }

    /** Mata (SIGKILL) todos os descendentes deste processo: proot, java, maven, gradle... */
    fun killDescendants(): Int {
        val parentOf = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            runCatching {
                // "pid (comm) S ppid ..." - o comm pode conter espacos e parenteses
                val fields = File(dir, "stat").readText().substringAfterLast(')').trim().split(' ')
                parentOf[pid] = fields[1].toInt()
            }
        }
        val victims = mutableSetOf<Int>()
        var frontier = setOf(android.os.Process.myPid())
        while (frontier.isNotEmpty()) {
            frontier = parentOf.filter { it.value in frontier && it.key !in victims }.keys
            victims += frontier
        }
        victims.forEach { android.os.Process.killProcess(it) }
        return victims.size
    }
}
