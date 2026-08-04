package com.pockettavern.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.pockettavern.app.data.local.CardExtensionSettings
import com.pockettavern.app.data.repository.LocalRepository
import com.pockettavern.app.util.DebugLogger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PocketTavernApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var cardExtensionSettings: CardExtensionSettings

    @Inject
    lateinit var localRepository: LocalRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Initialize debug logger
        DebugLogger.init(this)
        DebugLogger.log("PocketTavern App started")
        // Disable all card extensions on startup — they auto-enable when their card is opened
        cardExtensionSettings.disableAll()
        installPersonalStarterContent()

        // Set up global uncaught exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DebugLogger.logError("CRASH", "Uncaught exception on thread ${thread.name}", throwable)
            // Call default handler to let the app crash normally (shows crash dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun newImageLoader(): ImageLoader = imageLoader

    /** Installs the user's supplied card and preset once, without embedding any API key. */
    private fun installPersonalStarterContent() {
        val state = getSharedPreferences("ai_companion_starter", MODE_PRIVATE)
        applicationScope.launch {
            if (!state.getBoolean("character_v1", false)) {
                runCatching { assets.open("starter/秋浸月.png").use { it.readBytes() } }
                    .onSuccess { bytes ->
                        localRepository.importCharacterCardBytes(bytes)
                            .onSuccess {
                                state.edit().putBoolean("character_v1", true).apply()
                                DebugLogger.log("AI Companion: starter character installed")
                            }
                            .onError { DebugLogger.logError("AI Companion", "Starter character import failed", it) }
                    }
                    .onFailure { DebugLogger.logError("AI Companion", "Starter character asset missing", it) }
            }

            if (!state.getBoolean("preset_v1", false)) {
                runCatching {
                    assets.open("starter/梦境思客V1.json").bufferedReader().use { it.readText() }
                }.onSuccess { json ->
                    localRepository.importStOaiPreset("梦境思客V1", json)
                        .onSuccess {
                            localRepository.selectOaiPreset("梦境思客V1")
                            state.edit().putBoolean("preset_v1", true).apply()
                            DebugLogger.log("AI Companion: starter preset installed")
                        }
                        .onError { DebugLogger.logError("AI Companion", "Starter preset import failed", it) }
                }.onFailure { DebugLogger.logError("AI Companion", "Starter preset asset missing", it) }
            }
        }
    }
}
