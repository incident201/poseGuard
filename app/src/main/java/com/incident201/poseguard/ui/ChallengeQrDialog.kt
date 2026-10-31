package com.incident201.poseguard.ui

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.incident201.poseguard.R
import com.incident201.poseguard.challenge.QrCodeImages
import com.incident201.poseguard.challenge.QrCodes
import com.incident201.poseguard.viewmodel.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@SuppressLint("SourceLockedOrientationActivity") // The QR viewer is intentionally portrait only.
internal fun ChallengeQrDialog(code: String, title: String, language: AppLanguage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (previousOrientation != null) activity.requestedOrientation = previousOrientation
        }
    }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val savedText = localizedString(language, R.string.challenge_qr_saved)
    val errorText = localizedString(language, R.string.challenge_qr_save_error)
    val canEncode = QrCodes.canEncode(code)
    val save = {
        saving = true
        scope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    runCatching { QrCodeImages.save(context, code) }.isSuccess
                }
                Toast.makeText(context, if (success) savedText else errorText, Toast.LENGTH_LONG).show()
            } finally {
                saving = false
            }
        }
        Unit
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            // Change only this dialog's window: system settings and the Activity stay intact.
            val window = (view.parent as DialogWindowProvider).window
            val previousBrightness = window.attributes.screenBrightness
            val wasKeepingScreenOn = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            window.attributes = window.attributes.apply { screenBrightness = 1f }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
            onDispose {
                window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
                if (!wasKeepingScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        // A fixed light palette preserves contrast even when the app uses a dark theme.
        MaterialTheme(colorScheme = lightColorScheme()) {
            Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
                Box(Modifier.padding(horizontal = 12.dp)) { QrHeading(title, language, onDismiss) }
                QrImage(code, language, Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    QrSaveButton(language, saving, canEncode, save)
                }
            }
        }
    }
}

@Composable
private fun QrHeading(title: String, language: AppLanguage, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.Black, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, localizedString(language, R.string.final_close), tint = Color.Black)
        }
    }
}

@Composable
private fun QrSaveButton(language: AppLanguage, saving: Boolean, canEncode: Boolean, onSave: () -> Unit) {
    Button(onClick = onSave, enabled = canEncode && !saving, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(localizedString(language, if (saving) R.string.challenge_qr_saving else R.string.challenge_qr_save))
    }
}

@Composable
private fun QrImage(code: String, language: AppLanguage, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val sizePx = with(LocalDensity.current) { side.roundToPx().coerceAtLeast(1) }
        val bitmap by produceState<android.graphics.Bitmap?>(null, code, sizePx) {
            value = withContext(Dispatchers.Default) { QrCodes.encode(code, sizePx) }
        }
        if (!QrCodes.canEncode(code)) {
            Text(localizedString(language, R.string.challenge_qr_too_large), color = Color.Black,
                modifier = Modifier.padding(24.dp))
        } else {
            bitmap?.let {
                Image(bitmap = it.asImageBitmap(),
                    contentDescription = localizedString(language, R.string.challenge_show_qr),
                    filterQuality = FilterQuality.None, modifier = Modifier.size(side))
            }
        }
    }
}
