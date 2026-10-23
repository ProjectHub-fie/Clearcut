package com.projecthub.clearcut.di

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/** Small factory that constructs ViewModels from the app container. */
inline fun <reified VM : ViewModel> viewModelFactory(
    crossinline create: (Application, AppContainer) -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            ?: error("ClearCut requires an Application in the ViewModel store")
        val container = (app as com.projecthub.clearcut.app.ClearCutApplication).container
        return create(app, container) as T
    }
}