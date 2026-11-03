package io.github.jman9700.quartermaster.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.jman9700.quartermaster.ui.App
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deps = (application as QuarterMasterApp).dependencies
        setContent { App(deps) }
    }

    /** "Every time the app is opened", including coming back to it from the background. */
    override fun onStart() {
        super.onStart()
        val app = application as QuarterMasterApp
        app.appScope.launch { app.dependencies.lookup.processQueue() }
    }
}
