package net.rpcs3

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlin.concurrent.thread

/** Imports C:/arcade/rpcs3 while preserving one isolated virtual disk per profile. */
class TeknoParrotArcadeImportActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            text = "Select the rpcs3 folder containing the 11 TeknoParrot arcade game folders."
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(this@TeknoParrotArcadeImportActivity))
            addView(status)
        })
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, PICK_ROOT)
    }

    @Deprecated("Android still dispatches document-tree results here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_ROOT || resultCode != RESULT_OK || data?.data == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val uri = data.data!!
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val selected = DocumentFile.fromTreeUri(this, uri)
        if (selected == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        thread(name = "RPCS3X6 arcade import") { import(selected) }
    }

    private fun import(selected: DocumentFile) {
        val candidates = buildList {
            TeknoParrotArcadeCatalog.folderProfiles[selected.name]?.let { add(selected to it) }
            selected.listFiles().filter { it.isDirectory }.forEach { folder ->
                TeknoParrotArcadeCatalog.folderProfiles[folder.name]?.let { add(folder to it) }
            }
        }
        if (candidates.isEmpty()) return finishWith("No supported TeknoParrot RPCS3 arcade folders were found.", false)

        val catalog = TeknoParrotArcadeCatalog.load(this).associateBy { it.profileName }.toMutableMap()
        var imported = 0
        for ((source, profile) in candidates) {
            runOnUiThread { status.text = "Importing ${source.name}\n${imported + 1} of ${candidates.size}" }
            val destination = File(TeknoParrotArcadeCatalog.gameRoot(this), profile)
            val eboot = File(destination, EBOOT_RELATIVE)
            if (!eboot.isFile) {
                if (destination.exists()) {
                    // Resume an interrupted app-owned import in place. The
                    // catalog never exposes it until EBOOT.BIN is complete.
                    copyTree(source, destination)
                } else {
                    val staging = File(destination.parentFile, ".$profile.importing")
                    if (staging.exists()) staging.deleteRecursively()
                    staging.mkdirs()
                    copyTree(source, staging)
                    val stagedEboot = File(staging, EBOOT_RELATIVE)
                    if (!stagedEboot.isFile) {
                        staging.deleteRecursively()
                        continue
                    }
                    if (!staging.renameTo(destination)) {
                        staging.deleteRecursively()
                        continue
                    }
                }
            }
            if (eboot.isFile) {
                catalog[profile] = TeknoParrotArcadeGame(profile, destination.absolutePath, eboot.absolutePath)
                imported++
            }
        }
        TeknoParrotArcadeCatalog.save(this, catalog.values.toList())
        finishWith("RPCS3X6 imported $imported of ${candidates.size} supported arcade games.", imported > 0)
    }

    private fun copyTree(source: DocumentFile, destination: File) {
        source.listFiles().forEach { child ->
            val safeName = child.name?.takeIf { it != "." && it != ".." && !it.contains('/') } ?: return@forEach
            val target = File(destination, safeName)
            if (child.isDirectory) {
                target.mkdirs()
                copyTree(child, target)
            } else if (child.isFile) {
                contentResolver.openInputStream(child.uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                }
            }
        }
    }

    private fun finishWith(message: String, success: Boolean) {
        runOnUiThread {
            status.text = message
            setResult(if (success) RESULT_OK else RESULT_CANCELED)
            status.postDelayed({ finish() }, 1200)
        }
    }

    companion object {
        private const val PICK_ROOT = 357
        private const val EBOOT_RELATIVE = "dev_hdd0/game/SCEEXE000/USRDIR/EBOOT.BIN"
    }
}
