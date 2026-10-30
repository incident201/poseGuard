package com.incident201.poseguard.ui

import androidx.core.content.FileProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChallengeShareTest {
    private val context = RuntimeEnvironment.getApplication()
    private val cache get() = File(context.cacheDir, "challenge_share")

    @After fun cleanUp() { cache.deleteRecursively() }

    @Test fun sendingSameFileNameAgainKeepsBothAttachmentsReadableThroughTheirUris() {
        val first = ChallengeShare.stage(context, byteArrayOf(1, 2), "Task.pgtask")
        val firstUri = FileProvider.getUriForFile(context, context.packageName + ".challenges", first)
        val second = ChallengeShare.stage(context, byteArrayOf(3, 4), "Task.pgtask")
        val secondUri = FileProvider.getUriForFile(context, context.packageName + ".challenges", second)
        assertNotEquals(firstUri, secondUri)
        assertEquals("Task.pgtask", first.name)
        assertEquals(first.name, second.name)
        assertArrayEquals(byteArrayOf(1, 2), context.contentResolver.openInputStream(firstUri)!!.use { it.readBytes() })
        assertArrayEquals(byteArrayOf(3, 4), context.contentResolver.openInputStream(secondUri)!!.use { it.readBytes() })
    }

    @Test fun oldAttachmentsArePrunedWhileRecentSendsAreRetained() {
        val old = ChallengeShare.stage(context, byteArrayOf(1), "old.pgreport")
        val recent = ChallengeShare.stage(context, byteArrayOf(2), "recent.pgreport")
        assertTrue(old.parentFile!!.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000))
        ChallengeShare.stage(context, byteArrayOf(3), "next.pgtask")
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }
}
