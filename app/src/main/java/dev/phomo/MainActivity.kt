package dev.phomo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.phomo.ui.PhomoApp
import dev.phomo.ui.PhomoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val buildLabel = BuildProvenance.label(
            branch = BuildConfig.LOCAL_BUILD_BRANCH,
            sha = BuildConfig.LOCAL_BUILD_SHA,
            dirty = BuildConfig.LOCAL_BUILD_DIRTY,
            versionName = BuildConfig.VERSION_NAME,
        )
        setContent {
            PhomoTheme {
                PhomoApp(buildLabel = buildLabel)
            }
        }
    }
}
