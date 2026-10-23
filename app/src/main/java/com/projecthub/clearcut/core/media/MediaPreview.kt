package com.projecthub.clearcut.core.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/**
 * ExoPlayer-based preview with external seek support and playback-position
 * reporting.
 */
@Composable
fun MediaPreview(
    mediaUri: String,
    modifier: Modifier = Modifier,
    onPositionChange: (positionUs: Long) -> Unit = {},
    seekRequestUs: Long? = null,
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    var latestSeek by remember { mutableLongStateOf(-1L) }

    DisposableEffect(player, mediaUri) {
        player.setMediaItem(MediaItem.fromUri(mediaUri))
        player.prepare()
        latestSeek = -1L
        onDispose { player.release() }
    }

    // Report playback position while playing.
    androidx.compose.runtime.LaunchedEffect(player) {
        var polling: Job? = null
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    polling?.cancel()
                    polling = launch {
                        while (isActive) {
                            onPositionChange(player.currentPosition * 1000L)
                            delay(250)
                        }
                    }
                } else {
                    polling?.cancel()
                }
            }
        }
        player.addListener(listener)
        try {
            while (true) {
                kotlinx.coroutines.delay(50)
            }
        } finally {
            player.removeListener(listener)
            polling?.cancel()
        }
    }

    androidx.compose.runtime.LaunchedEffect(seekRequestUs) {
        val target = seekRequestUs ?: return@LaunchedEffect
        if (target != latestSeek) {
            latestSeek = target
            player.seekTo((target / 1000L).coerceAtLeast(0L))
            player.playWhenReady = true
        }
    }

    AndroidView(
        factory = { PlayerView(it).apply { useController = true; this.player = player } },
        modifier = modifier,
    )
}