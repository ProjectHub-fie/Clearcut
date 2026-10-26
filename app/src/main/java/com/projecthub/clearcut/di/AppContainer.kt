package com.projecthub.clearcut.di

import android.content.Context
import com.projecthub.clearcut.core.media.ExportRenderer
import com.projecthub.clearcut.core.media.MediaDecoders
import com.projecthub.clearcut.core.media.MediaInspector
import com.projecthub.clearcut.core.network.DirectMediaAdapter
import com.projecthub.clearcut.core.network.OkHttpDownloader
import com.projecthub.clearcut.core.network.SourceAdapterRegistry
import com.projecthub.clearcut.core.scanner.MultiMethodScanner
import com.projecthub.clearcut.core.storage.ClearCutDatabase
import com.projecthub.clearcut.core.storage.PreferencesStore
import com.projecthub.clearcut.core.storage.StorageHelper
import com.projecthub.clearcut.core.updates.UpdateChecker
import com.projecthub.clearcut.core.updates.UpdateManager

/** Manual DI container (a genuine helper; no framework dependency). */
class AppContainer(private val context: Context) {

    val preferencesStore by lazy { PreferencesStore(context.applicationContext) }
    val database by lazy { ClearCutDatabase.get(context.applicationContext) }
    val projectDao by lazy { database.projectDao() }
    val scanHistoryDao by lazy { database.scanHistoryDao() }

    val mediaInspector by lazy { MediaInspector(context.applicationContext) }
    val mediaDecoders by lazy { MediaDecoders(context.applicationContext) }
    val multiMethodScanner by lazy {
        MultiMethodScanner().also { scanner ->
            scanner.audioProvider = { media, settings, progress ->
                mediaDecoders.decodeAudioToPcm(media, settings, progress)
            }
            scanner.frameProvider = { media, settings, progress ->
                mediaDecoders.sampleFrames(media, settings, progress)
            }
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val exportRenderer by lazy { ExportRenderer(context.applicationContext) }

    val sourceAdapterRegistry by lazy {
        SourceAdapterRegistry(
            listOf(DirectMediaAdapter())
        )
    }
    val downloader by lazy { OkHttpDownloader(OkHttpDownloader.defaultClient()) }
    val updateChecker by lazy { UpdateChecker(UpdateChecker.defaultClient()) }
    val updateManager by lazy { UpdateManager(context.applicationContext, downloader) }
    val storageHelper by lazy { StorageHelper }

    /** The installed app version from the package manager (e.g. "1.1.0"). */
    val appVersionName: String
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
}