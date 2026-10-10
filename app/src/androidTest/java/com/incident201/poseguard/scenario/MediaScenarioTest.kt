package com.incident201.poseguard.scenario

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.incident201.poseguard.video.TimelapseRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MediaScenarioTest {
    @Test fun syntheticTimelapseCreatesPlayableAvcVideoAndCanRestart() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val recorder = TimelapseRecorder(app)
        val worker = recorderWorker(recorder)
        val image = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.BLUE)
        try {
            repeat(2) { recording ->
                recorder.start(0)
                recorder.startTimer(0)
                repeat(5) { index ->
                    recorder.offerFrame(image, index * 2_000L, recording, "%d violations")
                    // Wait for the offered frame to leave the bounded queue, including cold codec setup.
                    worker.submit {}.get(5, TimeUnit.SECONDS)
                }
                val file = recorder.stop()
                assertNotNull("Native encoder must produce an MP4", file)
                try { assertPlayableTimelapse(file!!, 320, 240, 3, expectedBlue = true) }
                finally { file?.delete() }
            }
        } finally {
            recorder.release()
            image.recycle()
        }
    }

    @Test fun emptyRecordingDiscardAndReleaseNeverLeavePlayableStaleVideo() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val recorder = TimelapseRecorder(app)
        val worker = recorderWorker(recorder)
        val image = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val before = timelapseFiles(app).toSet()
        try {
            recorder.start(0)
            assertNull(recorder.stop())
            recorder.start(0)
            recorder.offerFrame(image, 0, 3, "%d violations")
            worker.submit {}.get(5, TimeUnit.SECONDS)
            recorder.discard()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertFalse(recorder.isRecording)
            assertNull(recorder.stop())
            assertEquals(before, timelapseFiles(app).toSet())
            recorder.start(0)
            recorder.offerFrame(image, 0, 0, "%d violations")
            recorder.release()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(before, timelapseFiles(app).toSet())
            recorder.start(0)
            recorder.offerFrame(image, 1_000, 0, "%d violations")
            assertFalse(recorder.isRecording)
            assertNull(recorder.stop())
        } finally {
            recorder.release()
            image.recycle()
            (timelapseFiles(app).toSet() - before).forEach { it.delete() }
        }
    }
}

// Found by type rather than name so that the lookup also works after R8 renamed the field.
private fun recorderWorker(recorder: TimelapseRecorder) = TimelapseRecorder::class.java.declaredFields
    .single { ExecutorService::class.java.isAssignableFrom(it.type) }
    .apply { isAccessible = true }.get(recorder) as ExecutorService

internal fun timelapseFiles(app: Application): List<File> =
    app.cacheDir.listFiles { file -> file.name.startsWith("pose_timelapse_") && file.extension == "mp4" }?.toList().orEmpty()

internal fun assertPlayableTimelapse(file: File, width: Int? = null, height: Int? = null, minSamples: Int = 2, expectedBlue: Boolean = false) {
    assertTrue(file.length() > 0)
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        assertEquals(1, extractor.trackCount)
        val format = extractor.getTrackFormat(0)
        assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
        if (width != null) assertEquals(width, format.getInteger(MediaFormat.KEY_WIDTH))
        if (height != null) assertEquals(height, format.getInteger(MediaFormat.KEY_HEIGHT))
        extractor.selectTrack(0)
        val times = mutableListOf<Long>()
        while (extractor.sampleTime >= 0) { times.add(extractor.sampleTime); extractor.advance() }
        assertTrue("Expected at least $minSamples video samples, got ${times.size}", times.size >= minSamples)
        assertTrue(times.zipWithNext().all { (a, b) -> b > a })
        if (expectedBlue) assertTrue("Video timestamps must compress 8 seconds to less than one second", times.last() - times.first() in 200_000..900_000)
    } finally { extractor.release() }
    val decoder = MediaMetadataRetriever()
    try {
        decoder.setDataSource(file.absolutePath)
        val frame = decoder.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        assertNotNull("Android must decode the produced AVC", frame)
        frame!!.let {
            try {
                if (expectedBlue) {
                    val pixel = it.getPixel(it.width / 2, it.height / 2)
                    assertTrue(Color.blue(pixel) > 180 && Color.red(pixel) < 80)
                }
            } finally { it.recycle() }
        }
    } finally { decoder.release() }
}
