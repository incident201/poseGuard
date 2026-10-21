package com.incident201.poseguard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import com.incident201.poseguard.ui.CameraScreen
import com.incident201.poseguard.ui.theme.MyApplicationTheme
import com.incident201.poseguard.viewmodel.GameViewModel

class MainActivity : ComponentActivity() {
  private val viewModel: GameViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (savedInstanceState == null) handleChallengeIntent(intent)
    setContent {
      MyApplicationTheme {
        Scaffold(
          modifier = Modifier.fillMaxSize(),
          containerColor = MaterialTheme.colorScheme.background,
          contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { padding ->
          CameraScreen(viewModel = viewModel, modifier = Modifier.padding(padding))
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleChallengeIntent(intent)
  }

  /** Challenge and report files arrive from messengers as a viewed file, a shared file or shared text. */
  private fun handleChallengeIntent(intent: Intent?) {
    when (intent?.action) {
      Intent.ACTION_VIEW -> intent.data?.let(viewModel::importChallengeFromUri)
      Intent.ACTION_SEND -> {
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        when {
          stream != null -> viewModel.importChallengeFromUri(stream)
          !text.isNullOrBlank() -> viewModel.importChallengeFromText(text)
        }
      }
    }
  }
}
