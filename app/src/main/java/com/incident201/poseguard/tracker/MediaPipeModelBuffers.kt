package com.incident201.poseguard.tracker

import android.content.Context
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Keeps model bytes immutable and alive while native graphs overlap during recreation or CPU/GPU handoff. */
internal object MediaPipeModelBuffers {
    private val models = mutableMapOf<String, MappedByteBuffer>()

    @Synchronized
    fun load(context: Context, assetPath: String): ByteBuffer {
        val mapped = models.getOrPut(assetPath) {
            // Assets are packaged uncompressed. Map the APK itself instead of MediaPipe's
            // relative-path cache: creating another graph truncates that shared cache file
            // while existing graphs still read its mapping, causing native SIGBUS (#6369).
            context.applicationContext.assets.openFd(assetPath).use { asset ->
                asset.createInputStream().use { input ->
                    input.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
                }
            }
        }
        // Graphs get independent positions; the backing mapping lives for the process lifetime.
        return mapped.duplicate()
    }
}
