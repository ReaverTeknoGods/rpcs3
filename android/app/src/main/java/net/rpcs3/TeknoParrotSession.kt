package net.rpcs3

import android.content.Context
import android.content.Intent
import java.io.File

object TeknoParrotSession {
    @Volatile private var callbackPackage = ""
    @Volatile private var token = ""
    @Volatile private var profileName = ""
    @Volatile private var gamePath = ""
    @Volatile private var status = "stopped"

    fun accept(context: Context, intent: Intent): String? {
        if (intent.action != TeknoParrotContract.ACTION_LAUNCH_GAME) return null
        if (intent.getStringExtra(TeknoParrotContract.EXTRA_CALLBACK_PACKAGE) != TeknoParrotContract.TPUI_PACKAGE) return null

        val candidateToken = TeknoParrotContract.validToken(
            intent.getStringExtra(TeknoParrotContract.EXTRA_SESSION_TOKEN)
        ) ?: return null
        val candidatePath = intent.getStringExtra(TeknoParrotContract.EXTRA_GAME_PATH)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val root = context.getExternalFilesDir(null)?.canonicalFile ?: return null
        val resolved = File(candidatePath).let { if (it.isAbsolute) it else File(root, candidatePath) }
            .canonicalFile

        // TPUI launches content that has already been imported into the
        // companion's private external-files area. Never accept an arbitrary
        // path supplied by another process.
        if (!resolved.toPath().startsWith(root.toPath()) || !resolved.exists()) return null

        callbackPackage = TeknoParrotContract.TPUI_PACKAGE
        token = candidateToken
        profileName = intent.getStringExtra(TeknoParrotContract.EXTRA_PROFILE_NAME).orEmpty()
        gamePath = resolved.absolutePath
        status = "accepted"
        publish(context)
        return gamePath
    }

    fun owns(candidateToken: String): Boolean =
        token.isNotBlank() && token == candidateToken && callbackPackage == TeknoParrotContract.TPUI_PACKAGE

    fun update(context: Context, newStatus: String) {
        if (token.isBlank()) return
        status = newStatus
        publish(context)
        if (newStatus == "stopped" || newStatus == "failed") clear()
    }

    fun query(context: Context, requestToken: String) {
        if (owns(requestToken)) {
            publish(context)
        } else {
            send(context, requestToken, "stopped", "", "")
        }
    }

    private fun publish(context: Context) {
        send(context, token, status, profileName, gamePath)
    }

    private fun send(context: Context, sessionToken: String, sessionStatus: String, profile: String, path: String) {
        context.applicationContext.sendBroadcast(
            Intent(TeknoParrotContract.ACTION_SESSION_STATUS)
                .setPackage(TeknoParrotContract.TPUI_PACKAGE)
                .putExtra(TeknoParrotContract.EXTRA_SESSION_TOKEN, sessionToken)
                .putExtra(TeknoParrotContract.EXTRA_SESSION_STATUS, sessionStatus)
                .putExtra(TeknoParrotContract.EXTRA_PROFILE_NAME, profile)
                .putExtra(TeknoParrotContract.EXTRA_GAME_PATH, path)
        )
    }

    private fun clear() {
        callbackPackage = ""
        token = ""
        profileName = ""
        gamePath = ""
        status = "stopped"
    }
}
