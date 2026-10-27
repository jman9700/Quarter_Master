package io.github.jman9700.quartermaster.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.jman9700.quartermaster.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deps = (application as QuarterMasterApp).dependencies
        setContent { App(deps) }
    }
}
