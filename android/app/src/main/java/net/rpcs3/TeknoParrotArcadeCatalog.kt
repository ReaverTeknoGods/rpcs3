package net.rpcs3

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class TeknoParrotArcadeGame(val profileName: String, val rootPath: String, val ebootPath: String)

object TeknoParrotArcadeCatalog {
    val folderProfiles = linkedMapOf(
        "Dark Escape 4D (1.00.02)(2012)[Namco System 357][TP]" to "DarkEscape4D",
        "Deadstorm Pirate Special Edition (2.00)(2014)[Namco System 357][TP]" to "DSPS",
        "Dragon Ball Zenkai Battle Royale (3.26.01)(2017-12-25)[Namco System 357][TP]" to "dbzenkai",
        "Razing Storm (2009)[Namco System 357][TP]" to "RazingStorm",
        "Sailor Zombie AKB48 Arcade Edition (1.00)(2014)[Namco System 357][TP]" to "AKB48",
        "Taiko no Tatsujin Green Version (13.02)(2020-02-07)[Namco System 369][TP]" to "taikogreen",
        "Taiko no Tatsujin Yellow Version (10.01)(2017-02-06)[Namco System 369][TP]" to "taikoyellow",
        "Tekken 6 (2007)[Namco System 357][TP]" to "Tekken6",
        "Tekken 6 Bloodline Rebellion (2008)[Namco System 357][TP]" to "Tekken6BR",
        "Tekken Tag Tournament 2 (2011)[Namco System 369][TP]" to "ttt2",
        "Tekken Tag Tournament 2 Unlimited (1.00)(2011)[Namco System 369][TP]" to "ttt2u"
    )

    fun load(context: Context): List<TeknoParrotArcadeGame> = runCatching {
        val file = registry(context)
        if (!file.isFile) emptyList() else Json.decodeFromString<List<TeknoParrotArcadeGame>>(file.readText())
            .filter { game ->
                game.profileName in folderProfiles.values &&
                    File(game.rootPath).canonicalFile.toPath().startsWith(gameRoot(context).canonicalFile.toPath()) &&
                    File(game.ebootPath).isFile
            }
    }.getOrDefault(emptyList())

    fun save(context: Context, games: List<TeknoParrotArcadeGame>) {
        val file = registry(context)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".installing")
        temporary.writeText(Json.encodeToString(games.distinctBy { it.profileName }.sortedBy { it.profileName }))
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun gameRoot(context: Context) = File(context.getExternalFilesDir(null), "TeknoParrot/arcade")
    private fun registry(context: Context) = File(context.getExternalFilesDir(null), "TeknoParrot/arcade-games.json")
}
