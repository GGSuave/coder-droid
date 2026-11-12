package dev.codedroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/**
 * Mantem o code-server vivo (dentro de proot) enquanto o app estiver aberto ou em segundo plano.
 * Ser um foreground service tambem reduz as chances do Android matar o processo filho.
 */
class ServerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        if (!isRunning()) launch()
        // NOT_STICKY: se o Android matar o app, o servidor NAO ressuscita sozinho em segundo plano;
        // ele volta quando voce abrir o app de novo.
        return START_NOT_STICKY
    }

    /** O usuario fechou a janela no gerenciador de janelas: encerra servidor e notificacao. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopServer()
        stopForeground(true)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopServer()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ proc

    @Synchronized
    private fun launch() {
        val env = Env(this)
        lastError = null

        val server = listOf(
            "/opt/code-server/bin/code-server",
            "--bind-addr", "127.0.0.1:${Env.PORT}",
            "--auth", "none",
            "--disable-telemetry",
            "--disable-update-check",
            "--disable-workspace-trust",
            Env.WORKSPACE,
        )

        // Gancho de depuracao: se files/launch.sh existir, ele substitui o comando padrao.
        val override = java.io.File(env.home, "launch.sh")
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val pb = if (debuggable && override.exists()) {
            Proot.applyEnv(
                ProcessBuilder("/system/bin/sh", override.path, env.proot.path.substringBeforeLast('/'))
                    .directory(env.home).redirectErrorStream(true),
                env,
            )
        } else {
            Proot.builder(env, server, listOf("NODE_OPTIONS=--require=/opt/android-shim.js"))
        }

        process = try {
            pb.start().also { p -> pumpOutput(p, env.log) }
        } catch (e: Exception) {
            lastError = "Falha ao iniciar o proot: ${e.message}"
            android.util.Log.e(TAG, lastError!!, e)
            null
        }
    }

    /** Copia a saida do servidor para server.log e para o logcat (tag codedroid-server). */
    private fun pumpOutput(p: Process, log: java.io.File) {
        Thread({
            try {
                log.writer().use { w ->
                    p.inputStream.bufferedReader().forEachLine { line ->
                        w.write(line); w.write("\n"); w.flush()
                        android.util.Log.i(TAG, line)
                    }
                }
            } catch (_: Exception) {
            }
            android.util.Log.i(TAG, "processo terminou, codigo=" + runCatching { p.waitFor() }.getOrNull())
        }, "server-log").apply { isDaemon = true }.start()
    }

    private fun stopServer() {
        process?.let {
            android.util.Log.i(TAG, "parando servidor (vivo=${it.isAlive})")
            it.destroy()
            runCatching { Thread.sleep(300) }
            if (it.isAlive) it.destroyForcibly()
        }
        process = null
        // Rede de seguranca: nada que o servidor tenha iniciado pode sobreviver ao app.
        val killed = killDescendants()
        android.util.Log.i(TAG, "processos descendentes encerrados: $killed")
    }

    /** Mata (SIGKILL) todos os descendentes deste processo: proot, node, terminais, dev servers. */
    private fun killDescendants(): Int {
        val parentOf = HashMap<Int, Int>()
        java.io.File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            runCatching {
                // "pid (comm) S ppid ..." — o comm pode conter espacos e parenteses
                val fields = java.io.File(dir, "stat").readText().substringAfterLast(')').trim().split(' ')
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

    // ----------------------------------------------------------- notification

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "codedroid:server").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_stat_codedroid)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "dev.codedroid.STOP"
        private const val CHANNEL = "server"
        private const val NOTIF_ID = 1
        private const val TAG = "codedroid-server"

        @Volatile var process: Process? = null
        @Volatile var lastError: String? = null

        fun isRunning() = process?.isAlive == true

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ServerService::class.java))
        }
    }
}
