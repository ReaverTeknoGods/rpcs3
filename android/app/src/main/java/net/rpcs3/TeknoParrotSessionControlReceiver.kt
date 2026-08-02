package net.rpcs3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.concurrent.thread

class TeknoParrotSessionControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val request = intent ?: return
        if (request.getStringExtra(TeknoParrotContract.EXTRA_CALLBACK_PACKAGE) != TeknoParrotContract.TPUI_PACKAGE) return
        val token = TeknoParrotContract.validToken(
            request.getStringExtra(TeknoParrotContract.EXTRA_SESSION_TOKEN)
        ) ?: return

        when (request.action) {
            TeknoParrotContract.ACTION_QUERY_SESSION -> TeknoParrotSession.query(context, token)
            TeknoParrotContract.ACTION_STOP_GAME -> stopGame(context, token)
            TeknoParrotContract.ACTION_QUERY_CATALOG -> sendCatalog(context, token)
            TeknoParrotContract.ACTION_QUERY_FIRMWARE -> sendFirmwareStatus(context, token)
        }
    }

    private fun stopGame(context: Context, token: String) {
        if (!TeknoParrotSession.owns(token)) {
            TeknoParrotSession.query(context, token)
            return
        }

        val pending = goAsync()
        thread(name = "RPCS3X6 TPUI stop") {
            runCatching {
                if (RPCS3.initialized && RPCS3.getState() != EmulatorState.Stopped) {
                    RPCS3.instance.kill()
                }
                RPCS3Activity.finishActiveSession()
                TeknoParrotSession.update(context, "stopped")
            }
            pending.finish()
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
            val firmware = File(root, "fw.json")
            if (!firmware.isFile) return@runCatching false
            val status = Json.parseToJsonElement(firmware.readText())
                .jsonObject["status"]
                ?.jsonPrimitive
                ?.content
            status == FirmwareStatus.Installed.name || status == FirmwareStatus.Compiled.name
        }.getOrDefault(false)

        context.applicationContext.sendBroadcast(
            Intent(TeknoParrotContract.ACTION_FIRMWARE_STATUS)
                .setPackage(TeknoParrotContract.TPUI_PACKAGE)
                .putExtra(TeknoParrotContract.EXTRA_SESSION_TOKEN, token)
                .putExtra(TeknoParrotContract.EXTRA_FIRMWARE_READY, ready)
        )
    }
}
