package com.projecthub.clearcut.app

import android.app.Application
import com.projecthub.clearcut.di.AppContainer

class ClearCutApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

fun android.content.Context.appContainer(): AppContainer =
    (applicationContext as ClearCutApplication).container