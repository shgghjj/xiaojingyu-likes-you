package com.pockettavern.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettavern.app.ui.audio.ThemeAudioManager
import com.pockettavern.app.ui.navigation.SillyTavernNavGraph
import com.pockettavern.app.ui.screens.help.LicenseNoticeScreen
import com.pockettavern.app.ui.screens.help.TutorialScreen
import com.pockettavern.app.ui.theme.SillyTavernTheme
import com.pockettavern.app.ui.theme.ThemeManager
import com.pockettavern.app.util.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var themeAudioManager: ThemeAudioManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeColors by themeManager.colors.collectAsStateWithLifecycle()
            val particleEffect by themeManager.particleEffect.collectAsStateWithLifecycle()
            val themeAssets by themeManager.themeAssets.collectAsStateWithLifecycle()
            SillyTavernTheme(colors = themeColors, particleEffect = particleEffect, themeAssets = themeAssets) {
                val context = LocalContext.current
                val tutorialPrefs = remember {
                    context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
                }
                var showTutorial by remember {
                    // v2 是手机轻量版教程：升级用户只补看一次，之后仍可从设置重新打开。
                    mutableStateOf(!tutorialPrefs.getBoolean("completed_xiaojingyu_v2", false))
                }
                var showLicense by remember {
                    mutableStateOf(!tutorialPrefs.getBoolean("license_accepted_v1", false))
                }
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    if (showTutorial) {
                        TutorialScreen(
                            onFinish = {
                                tutorialPrefs.edit().putBoolean("completed_xiaojingyu_v2", true).apply()
                                showTutorial = false
                            }
                        )
                    } else if (showLicense) {
                        LicenseNoticeScreen(
                            onAccept = {
                                tutorialPrefs.edit().putBoolean("license_accepted_v1", true).apply()
                                showLicense = false
                            }
                        )
                    } else {
                        SillyTavernNavGraph(themeAudioManager = themeAudioManager)
                    }
                }
            }
        }
    }
}
