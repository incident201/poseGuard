package com.incident201.poseguard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Hands challenge and report files to other apps through the challenge FileProvider. */
internal object ChallengeShare {
    private const val SHARE_DIR = "challenge_share"

    /** Writes [bytes] to the share cache; call off the main thread. */
    fun stage(context: Context, bytes: ByteArray, fileName: String): File {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, fileName).apply { writeBytes(bytes) }
    }

    fun shareFile(context: Context, file: File, chooserTitle: String, text: String? = null) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".challenges", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }

    fun shareText(context: Context, text: String, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }

    fun copyText(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun pasteText(context: Context): String? =
        context.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()

    /** File names keep only letters and digits of the title, so every messenger accepts them. */
    fun fileName(title: String, extension: String): String {
        val base = title.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().replace(' ', '_').take(40)
        return (base.ifEmpty { "poseguard" }) + "." + extension
    }
}
