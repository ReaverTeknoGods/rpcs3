package net.rpcs3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.rpcs3.ui.navigation.AppNavHost

class MainActivity : ComponentActivity() {
    private var unregisterUsbEventListener: () -> Unit = {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!RPCS3Runtime.ensureInitialized(applicationContext)) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            RPCS3Theme {
                AppNavHost()
            }
        }

        lifecycleScope.launch { GameRepository.load() }
        FirmwareRepository.load()

        Permission.PostNotifications.requestPermission(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            val appPermission = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            runCatching { startActivity(appPermission) }
                .getOrElse {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
        }

        with(getSystemService(NOTIFICATION_SERVICE) as NotificationManager) {
            val channel = NotificationChannel(
                "rpcs3-progress",
                "Installation progress",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            createNotificationChannel(channel)
        }

        unregisterUsbEventListener = listenUsbEvents(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbEventListener()
    }
}
