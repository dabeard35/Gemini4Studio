package com.gemini4studio

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.gemini4studio.ui.StudioScreen
import com.gemini4studio.ui.StudioViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashPrefs = getSharedPreferences("crash_diag", Context.MODE_PRIVATE)
        val previousCrash = crashPrefs.getString("last_crash", null)

        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = Log.getStackTraceString(throwable)
            crashPrefs.edit().putString("last_crash", trace).commit()
            prevHandler?.uncaughtException(thread, throwable)
        }

        runCatching { enableEdgeToEdge() }

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return StudioViewModel(application) as T
            }
        }

        val vmResult = runCatching {
            ViewModelProvider(this, factory)[StudioViewModel::class.java]
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var crashText by remember { mutableStateOf(previousCrash) }

                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        crashText != null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .padding(16.dp)
                            ) {
                                Text("Previous Crash Log", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = {
                                    crashPrefs.edit().remove("last_crash").apply()
                                    crashText = null
                                }) { Text("Clear & Launch App") }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = crashText.orEmpty(),
                                    modifier = Modifier.verticalScroll(rememberScrollState()),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        vmResult.isFailure -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text("Startup Error", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(8.dp))
                                Text(Log.getStackTraceString(vmResult.exceptionOrNull()))
                            }
                        }
                        else -> {
                            StudioScreen(viewModel = vmResult.getOrThrow())
                        }
                    }
                }
            }
        }
    }
}
