package net.rpcs3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

class TeknoParrotSessionControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val request = intent ?: return
        if (request.getStringExtra(TeknoParrotContract.EXTRA_CALLBACK_PACKAGE) != TeknoParrotContract.TPUI_PACKAGE) return
        val token = TeknoParrotContract.validToken(
            request.getStringExtra(TeknoParrotContract.EXTRA_SESSION_TOKEN)
        ) ?: return

        when (request.action) {
            TeknoParrotContract.ACTION_QUERY_SESSION -> querySession(context, token)
            TeknoParrotContract.ACTION_STOP_GAME -> stopGame(context, token)
            TeknoParrotContract.ACTION_QUERY_CATALOG -> {
                sendCatalog(context, token)
                scheduleIdleReceiverCleanup()
            }
            TeknoParrotContract.ACTION_QUERY_FIRMWARE -> {
                sendFirmwareStatus(context, token)
                scheduleIdleReceiverCleanup()
            }
        }
    }

    private fun querySession(context: Context, token: String) {
        val ownsSession = TeknoParrotSession.owns(token)
        TeknoParrotSession.query(context, token)

        // Android may start a fresh application process solely to deliver a
        // final health query after the terminal session process has exited.
        // Let the stopped callback reach TPUI, then discard that idle bridge
        // process instead of retaining RPCS3's native libraries in RAM.
        if (!ownsSession && !RPCS3Activity.hasActiveActivity()) {
            scheduleIdleReceiverCleanup()
        }
    }

    private fun scheduleIdleReceiverCleanup() {
        if (RPCS3Activity.hasActiveActivity()) return
        val pending = goAsync()
        thread(name = "RPCS3X6 idle receiver cleanup") {
            // Release the incoming broadcast queue first. The response sent
            // above is asynchronous and can otherwise remain queued behind
            // this PendingResult until the process is terminated.
            pending.finish()
            Thread.sleep(500)
            // TPUI can launch RPCS3Activity into this same process as soon as
            // it receives a successful preflight reply. Re-evaluate idleness
            // at exit time so that transition is never mistaken for a cached
            // receiver-only process.
            if (!RPCS3Activity.hasActiveActivity()) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun stopGame(context: Context, token: String) {
        if (!TeknoParrotSession.owns(token)) {
            querySession(context, token)
            return
        }

        val pending = goAsync()
        thread(name = "RPCS3X6 TPUI stop") {
            var cleanStopComplete = false
            runCatching {
                cleanStopComplete = RPCS3Activity.stopEmulatorAndWait()
                TeknoParrotSession.update(context, "stopped")
            }
            // Complete the broadcast before finishing the Activity. Its
            // terminal onDestroy intentionally kills this process, and doing
            // that with an outstanding PendingResult makes Android redeliver
            // STOP_GAME into a fresh cached process.
            pending.finish()
            Thread.sleep(250)
            RPCS3Activity.finishActiveSession(cleanStopComplete)
        }
    }

    private fun sendCatalog(context: Context, token: String) {
        val games = TeknoParrotArcadeCatalog.load(context)
        context.applicationContext.sendBroadcast(
            Intent(TeknoParrotContract.ACTION_CATALOG_STATUS)
                .setPackage(TeknoParrotContract.TPUI_PACKAGE)
                .putExtra(TeknoParrotContract.EXTRA_SESSION_TOKEN, token)
                .putStringArrayListExtra(TeknoParrotContract.EXTRA_GAME_PATHS, ArrayList(games.map { it.ebootPath }))
                .putStringArrayListExtra(TeknoParrotContract.EXTRA_PROFILE_NAMES, ArrayList(games.map { it.profileName }))
        )
    }

    private fun sendFirmwareStatus(context: Context, token: String) {
        val ready = runCatching {
            val root = context.getExternalFilesDir(null) ?: return@runCatching false
            FirmwareRepository.isReady(root)
        }.getOrDefault(false)

        context.applicationContext.sendBroadcast(
            Intent(TeknoParrotContract.ACTION_FIRMWARE_STATUS)
                .setPackage(TeknoParrotContract.TPUI_PACKAGE)
                .putExtra(TeknoParrotContract.EXTRA_SESSION_TOKEN, token)
                .putExtra(TeknoParrotContract.EXTRA_FIRMWARE_READY, ready)
        )
    }
}
