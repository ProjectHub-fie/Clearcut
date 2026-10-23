package com.projecthub.clearcut.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projecthub.clearcut.core.designsystem.ClearCutTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClearCutThemeContent()
        }
    }

    companion object {
        fun selfPendingIntent(context: android.content.Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

@Composable
private fun ClearCutThemeContent() {
    val container = LocalContext.current.appContainer()
    val prefs by container.preferencesStore.preferences.collectAsStateWithLifecycle(initialValue = null)
    val dark = prefs?.darkTheme

    ClearCutTheme(
        darkTheme = when (dark) {
            null -> androidx.compose.foundation.isSystemInDarkTheme()
            true -> true
            false -> false
        }
    ) {
        ClearCutApp()
    }
}