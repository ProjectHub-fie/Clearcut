package com.projecthub.clearcut.core.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.projecthub.clearcut.app.MainActivity
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.Edl
import com.projecthub.clearcut.core.model.RenderRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service for long-running, user-initiated rendering so exports
 * survive the user leaving the screen.
 */
@androidx.media3.common.util.UnstableApi
class ExportForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var renderJob: Job? = null
    private var progress = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI) ?: run { stopSelf(); return START_NOT_STICKY }
                val edlJson = intent.getStringExtra(EXTRA_EDL_JSON) ?: run { stopSelf(); return START_NOT_STICKY }
                val outputPath = intent.getStringExtra(EXTRA_OUTPUT) ?: run { stopSelf(); return START_NOT_STICKY }
                val request = try {
                    val edl = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString(Edl.serializer(), edlJson)
                    RenderRequest(
                        sourceUri = sourceUri,
                        edl = edl,
                        outputPath = outputPath,
                    )
                } catch (e: Exception) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundCompat()
                renderJob?.cancel()
                renderJob = startRender(request)
            }
            ACTION_CANCEL -> {
                renderJob?.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRender(request: RenderRequest): Job = scope.launch {
        try {
            val renderer = ExportRenderer(this@ExportForegroundService)
            val result = renderer.render(request) { p ->
                progress = p
                scope.launch(Dispatchers.Main) { updateNotification("Rendering…", p) }
            }
            updateNotification("Export complete", 1f)
            BroadcastNotifier.broadcastSuccess(this@ExportForegroundService, result.outputPath)
        } catch (e: ClearCutError) {
            updateNotification(if (e is ClearCutError.CancelledOperation) "Export cancelled" else "Export failed", 0f)
            BroadcastNotifier.broadcastError(this@ExportForegroundService, e)
        } catch (t: Throwable) {
            BroadcastNotifier.broadcastError(this@ExportForegroundService, ClearCutError.fromThrowable(t, "export"))
        } finally {
            stopSelf()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Preparing export…", 0f)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String, p: Float) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text, p))
    }

    private fun buildNotification(text: String, p: Float): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ExportForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ClearCut export")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(100, (p * 100).toInt(), p <= 0f)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .setContentIntent(MainActivity.selfPendingIntent(this))
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Exports", NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = "Long-running export progress"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "clearcut_exports"
        private const val NOTIF_ID = 9402
        const val ACTION_START = "com.projecthub.clearcut.export.START"
        const val ACTION_CANCEL = "com.projecthub.clearcut.export.CANCEL"
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_EDL_JSON = "edl_json"
        const val EXTRA_OUTPUT = "output"

        fun start(context: Context, sourceUri: String, edl: Edl, outputPath: String) {
            val intent = Intent(context, ExportForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE_URI, sourceUri)
                putExtra(EXTRA_EDL_JSON, kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.encodeToString(Edl.serializer(), edl))
                putExtra(EXTRA_OUTPUT, outputPath)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ExportForegroundService::class.java).apply { action = ACTION_CANCEL }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/** Simple process-local broadcasts for the export progress + result. */
object BroadcastNotifier {
    const val ACTION_PROGRESS = "clearcut.export.progress"
    const val ACTION_SUCCESS = "clearcut.export.success"
    const val ACTION_ERROR = "clearcut.export.error"
    private var listeners = mutableSetOf<(ExportEvent) -> Unit>()

    fun addListener(l: (ExportEvent) -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: (ExportEvent) -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    fun broadcastSuccess(context: Context, path: String) {
        synchronized(listeners) { listeners.toList() }.forEach { it(ExportEvent.Success(path)) }
    }

    fun broadcastError(context: Context, error: ClearCutError) {
        synchronized(listeners) { listeners.toList() }.forEach { it(ExportEvent.Error(error)) }
    }
}

sealed class ExportEvent {
    data class Success(val path: String) : ExportEvent()
    data class Error(val error: ClearCutError) : ExportEvent()
}