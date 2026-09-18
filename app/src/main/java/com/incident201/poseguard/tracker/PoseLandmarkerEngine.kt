package com.incident201.poseguard.tracker

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

internal interface PoseLandmarkerEngine : AutoCloseable {
    fun detectAsync(image: MPImage, timestampMs: Long)
}

internal fun interface PoseLandmarkerFactory {
    fun create(
        delegate: Delegate,
        onResult: (PoseLandmarks, Int, Int, Long) -> Unit,
        onError: (RuntimeException) -> Unit
    ): PoseLandmarkerEngine
}

/** Native API boundary; delegate lifecycle policy is kept in PoseLandmarkerService. */
internal class MediaPipePoseLandmarkerFactory(
    private val context: Context,
    private val model: PoseLandmarkerModel
) : PoseLandmarkerFactory {
    override fun create(
        delegate: Delegate,
        onResult: (PoseLandmarks, Int, Int, Long) -> Unit,
        onError: (RuntimeException) -> Unit
    ): PoseLandmarkerEngine {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(model.assetPath).setDelegate(delegate).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.70f)
            .setMinPosePresenceConfidence(0.70f)
            .setMinTrackingConfidence(0.75f)
            .setResultListener { result, image ->
                val landmarks = result.landmarks().firstOrNull()
                val pose = if (landmarks == null || landmarks.size < 33) {
                    PoseLandmarks()
                } else {
                    PoseLandmarks.fromAllLandmarks(landmarks.map {
                        Point3D(it.x(), it.y(), it.z(), it.visibility().orElse(null), it.presence().orElse(null))
                    })
                }
                onResult(pose, image.width, image.height, result.timestampMs())
            }
            .setErrorListener(onError)
            .build()
        val landmarker = PoseLandmarker.createFromOptions(context, options)
        return object : PoseLandmarkerEngine {
            override fun detectAsync(image: MPImage, timestampMs: Long) = landmarker.detectAsync(image, timestampMs)
            override fun close() = landmarker.close()
        }
    }
}
