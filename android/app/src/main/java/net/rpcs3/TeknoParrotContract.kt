package net.rpcs3

object TeknoParrotContract {
    const val TPUI_PACKAGE = "com.teknoparrot.ui"

    const val ACTION_LAUNCH_GAME = "com.teknoparrot.rpcs3x6.action.LAUNCH_GAME"
    const val ACTION_SETUP = "com.teknoparrot.rpcs3x6.action.SETUP"
    const val ACTION_QUERY_SESSION = "com.teknoparrot.rpcs3x6.action.QUERY_SESSION"
    const val ACTION_STOP_GAME = "com.teknoparrot.rpcs3x6.action.STOP_GAME"
    const val ACTION_SESSION_STATUS = "com.teknoparrot.rpcs3x6.action.SESSION_STATUS"
    const val ACTION_QUERY_CATALOG = "com.teknoparrot.rpcs3x6.action.QUERY_CATALOG"
    const val ACTION_CATALOG_STATUS = "com.teknoparrot.rpcs3x6.action.CATALOG_STATUS"
    const val ACTION_QUERY_FIRMWARE = "com.teknoparrot.rpcs3x6.action.QUERY_FIRMWARE"
    const val ACTION_FIRMWARE_STATUS = "com.teknoparrot.rpcs3x6.action.FIRMWARE_STATUS"
    const val ACTION_IMPORT_ARCADE_ROOT = "com.teknoparrot.rpcs3x6.action.IMPORT_ARCADE_ROOT"

    const val EXTRA_GAME_PATH = "com.teknoparrot.rpcs3x6.extra.GAME_PATH"
    const val EXTRA_PROFILE_NAME = "com.teknoparrot.rpcs3x6.extra.PROFILE_NAME"
    const val EXTRA_CALLBACK_PACKAGE = "com.teknoparrot.rpcs3x6.extra.CALLBACK_PACKAGE"
    const val EXTRA_SESSION_TOKEN = "com.teknoparrot.rpcs3x6.extra.SESSION_TOKEN"
    const val EXTRA_SESSION_STATUS = "com.teknoparrot.rpcs3x6.extra.SESSION_STATUS"
    const val EXTRA_GAME_PATHS = "com.teknoparrot.rpcs3x6.extra.GAME_PATHS"
    const val EXTRA_PROFILE_NAMES = "com.teknoparrot.rpcs3x6.extra.PROFILE_NAMES"
    const val EXTRA_FIRMWARE_READY = "com.teknoparrot.rpcs3x6.extra.FIRMWARE_READY"

    fun validToken(value: String?): String? =
        value?.takeIf {
            it.length in 32..128 &&
                it.all { character ->
                    character.isLetterOrDigit() || character == '-' || character == '_'
                }
        }
}
