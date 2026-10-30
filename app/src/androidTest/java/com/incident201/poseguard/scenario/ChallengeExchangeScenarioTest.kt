package com.incident201.poseguard.scenario

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.incident201.poseguard.MainActivity
import com.incident201.poseguard.challenge.AuthoredChallenge
import com.incident201.poseguard.challenge.ChallengeFiles
import com.incident201.poseguard.challenge.ChallengeRepository
import com.incident201.poseguard.challenge.ChallengeRules
import com.incident201.poseguard.challenge.ChallengeSessionRecorder
import com.incident201.poseguard.challenge.ChallengeSpec
import com.incident201.poseguard.challenge.ChallengeVisibility
import com.incident201.poseguard.challenge.QrCodes
import com.incident201.poseguard.challenge.SessionEndCause
import com.incident201.poseguard.challenge.TimerSpec
import com.incident201.poseguard.ui.ChallengeShare
import com.incident201.poseguard.viewmodel.RuleViolationCounts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How challenges reach the app on a real device: files and text shared by messengers into
 * MainActivity (cold start and while it runs), FileProvider URIs and QR codes. The app's own
 * storage plays one participant, a separate test storage the other; both use the device's real
 * file sealer and Keystore.
 */
@RunWith(AndroidJUnit4::class)
class ChallengeExchangeScenarioTest {
    private lateinit var app: Application
    private lateinit var settings: PreferencesSnapshot
    private lateinit var appRepository: ChallengeRepository
    private lateinit var other: ChallengeRepository
    private val receivedIds = mutableListOf<com.incident201.poseguard.challenge.Blob>()
    private val authoredIds = mutableListOf<com.incident201.poseguard.challenge.Blob>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        settings = PreferencesSnapshot(app.getSharedPreferences("game_settings", Context.MODE_PRIVATE))
        appRepository = ChallengeRepository(app)
        other = ChallengeRepository(app, storageName = "test_exchange_other")
    }

    @After fun tearDown() {
        receivedIds.forEach(appRepository::deleteReceived)
        authoredIds.forEach(appRepository::deleteAuthored)
        other.deleteStorage()
        settings.close()
    }

    private fun ChallengeRepository.create(): AuthoredChallenge = createChallenge(
        "Exchange test", "", "Tester", ChallengeRules(timer = TimerSpec(exactSeconds = 60)), ChallengeVisibility(), 0
    )

    /** A task from someone else, as the bytes that arrive on this phone. */
    private fun foreignTask(): Pair<ChallengeSpec, ByteArray> {
        val spec = other.create().spec
        receivedIds += spec.id
        return spec to other.taskFile(spec)
    }

    private fun shared(bytes: ByteArray, name: String): Uri =
        FileProvider.getUriForFile(app, app.packageName + ".challenges", ChallengeShare.stage(app, bytes, name))

    private fun intent(action: String) = Intent(action).setClass(app, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun viewIntent(uri: Uri) = intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")

    private fun streamIntent(uri: Uri) = intent(Intent.ACTION_SEND).setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .also { it.clipData = ClipData.newRawUri("", uri) }

    private fun textIntent(text: String) = intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    private fun awaitReceived(spec: ChallengeSpec) =
        awaitCondition("task in Received", 10_000) { appRepository.receivedChallenge(spec.id)?.spec == spec }

    @Test fun openingATaskFileStartsTheAppAndImportsIt() {
        val (spec, file) = foreignTask()
        ActivityScenario.launch<MainActivity>(viewIntent(shared(file, "task.pgtask"))).use { awaitReceived(spec) }
    }

    @Test fun aTaskFileSharedFromAMessengerIsImported() {
        val (spec, file) = foreignTask()
        ActivityScenario.launch<MainActivity>(streamIntent(shared(file, "task.pgtask"))).use { awaitReceived(spec) }
    }

    @Test fun aTextCodeSharedFromAMessengerIsImported() {
        val (spec, file) = foreignTask()
        val message = "Challenge in PoseGuard:\n${ChallengeFiles.toTextCode(file)}\nGood luck"
        ActivityScenario.launch<MainActivity>(textIntent(message)).use { awaitReceived(spec) }
    }

    @Test fun filesSharedWhileTheAppRunsReachTheRunningInstance() {
        val (first, firstFile) = foreignTask()
        val (second, secondFile) = foreignTask()
        // Started the way a messenger starts it. ActivityScenario is not used here: it finds its
        // activity through the launch intent, which onNewIntent replaces.
        app.startActivity(viewIntent(shared(firstFile, "first.pgtask")))
        awaitReceived(first)
        val running = awaitResumedMainActivity()
        try {
            onMain { running.startActivity(textIntent(ChallengeFiles.toTextCode(secondFile)).setFlags(0)) }
            awaitReceived(second)
            // singleTask: the share went to the running instance through onNewIntent.
            assertTrue(awaitResumedMainActivity() === running)
            assertEquals(Intent.ACTION_SEND, running.intent.action)
        } finally {
            onMain { running.finish() }
            awaitCondition("activity closed", 10_000) { resumedMainActivity() == null }
        }
    }

    private fun resumedMainActivity(): MainActivity? {
        var found: MainActivity? = null
        onMain {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull()
        }
        return found
    }

    private fun awaitResumedMainActivity(): MainActivity {
        awaitCondition("MainActivity in front", 10_000) { resumedMainActivity() != null }
        return resumedMainActivity()!!
    }

    @Test fun aReportSharedBackReachesTheAuthor() {
        val authored = appRepository.create()
        authoredIds += authored.spec.id
        val spec = (other.import(appRepository.taskFile(authored.spec)) as com.incident201.poseguard.challenge.ImportResult.TaskImported).challenge.spec
        val attempt = other.beginAttempt(spec)!!
        val report = ChallengeSessionRecorder(spec, attempt, "test", 1_000L, System.currentTimeMillis())
            .finish(SessionEndCause.Surrendered, 2_000L, System.currentTimeMillis(), 60, 1, RuleViolationCounts(), false)
        other.saveOutgoingReport(report)
        val reportFile = other.reportFile(report, "Executor", "from another phone")!!

        ActivityScenario.launch<MainActivity>(streamIntent(shared(reportFile, "report.pgreport"))).use {
            awaitCondition("report for the author", 10_000) {
                appRepository.incomingReport(report.reportId)?.report?.executorComment == "from another phone"
            }
        }
    }

    @Test fun qrCodesOfTypicalTasksAreReadBackFromACameraFrame() {
        val (_, file) = foreignTask()
        val code = ChallengeFiles.toTextCode(file)
        assertTrue("code of ${code.length} chars must fit a QR code", QrCodes.canEncode(code))
        val qr = QrCodes.encode(code, 900)!!
        assertEquals(code, QrCodes.decode(qr))

        // As the camera sees a phone screen: smaller, off-centre, on a grey background.
        val frame = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.GRAY)
            drawBitmap(Bitmap.createScaledBitmap(qr, 620, 620, false), 400f, 200f, null)
        }
        assertEquals(code, QrCodes.decode(frame))
        assertEquals(null, QrCodes.decode(Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)))
    }
}
