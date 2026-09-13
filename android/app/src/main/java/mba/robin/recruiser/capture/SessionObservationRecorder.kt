package mba.robin.recruiser.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import mba.robin.recruiser.core.CameraPose
import mba.robin.recruiser.core.QualityAnalyzer
import mba.robin.recruiser.core.TrackingQuality
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal class SessionObservationRecorder(
    context: Context,
    private val writer: CapturePackageWriter,
    private val segment: JSONObject,
    private val cameraId: String,
    private val onGuidance: (String) -> Unit,
    private val onError: (String) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensors = HandlerThread("capture-imu").apply { start() }
    private val retention = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32))
    private val analysis = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        ThreadPoolExecutor.DiscardOldestPolicy())
    private val analyzer = QualityAnalyzer()
    private var lastSample = 0L
    private var lastAnalysis = 0L
    private var lastFrame = 0L
    private var lastDepth = 0L
    private var depthCalibrationRecorded = false
    private var lastTracking: TrackingState? = null
    private var frameIndex = 0
    @Volatile private var stopped = false
    private val sharedClock = runCatching {
        context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    }.getOrDefault(false)

    init {
        var count = 0
        for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor != null && sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(sensors.looper))) count++
            else writer.event("sensor-unavailable", "Android sensor type $type is unavailable")
        }
        writer.capability("imu", count == 2)
    }

    fun record(frame: Frame) {
        if (stopped || frame.timestamp == 0L || frame.timestamp == lastFrame) return
        if (frame.timestamp < lastFrame) {
            writer.event("clock-reset", "Camera clock decreased from $lastFrame to ${frame.timestamp}; capture must resume in a new world frame")
            throw IllegalStateException("Camera clock changed. Saved observations are retained; Resume starts an independent scan.")
        }
        val timestamp = frame.timestamp
        lastFrame = timestamp
        if (segment.getString("startTimestampNs") == "0") {
            writer.startSegmentClock(segment, timestamp, sharedClock)
        }
        val camera = frame.camera
        val intrinsics = camera.imageIntrinsics
        val size = intrinsics.imageDimensions
        val focal = intrinsics.focalLength
        val center = intrinsics.principalPoint
        // Raw depth follows the GPU texture crop, not necessarily the CPU image crop.
        // ARCore Raw Depth codelab uses textureIntrinsics for this unprojection.
        val depthCamera = camera.textureIntrinsics
        val depthSize = depthCamera.imageDimensions
        val depthFocal = depthCamera.focalLength
        val depthCenter = depthCamera.principalPoint
        val calibration = writer.calibration(cameraId, size[0], size[1], focal, center)
        val tracking = camera.trackingState
        if (tracking != lastTracking) {
            writer.event("tracking-transition", "$lastTracking -> $tracking: ${camera.trackingFailureReason}", timestamp, "arcore-camera")
            lastTracking = tracking
        }
        val pose = if (tracking == TrackingState.TRACKING) camera.pose else null
        val frameId = "${segment.getString("id")}-${frameIndex++}"
        val record = JSONObject().put("id", frameId).put("segmentId", segment.getString("id"))
            .put("worldFrameId", segment.getString("worldFrameId")).put("timestampNs", timestamp.toString())
            .put("clockId", "arcore-camera").put("calibrationId", calibration).put("tracking", tracking.name)
            .put("trackingFailure", if (tracking == TrackingState.TRACKING) JSONObject.NULL else camera.trackingFailureReason.name)
            .put("pose", if (pose == null) JSONObject.NULL else JSONObject()
                .put("position", JSONArray(pose.translation.toList())).put("quaternion", JSONArray(pose.rotationQuaternion.toList())))
            .put("image", JSONObject.NULL).put("depth", JSONObject.NULL)
        writer.capability("camera", true)
        if (pose != null) writer.capability("pose", true)
        var sampled: ImageSample? = null
        var depth: DepthSample? = null
        if (timestamp - lastSample >= 200_000_000L) {
            lastSample = timestamp
            try { frame.acquireCameraImage().use { sampled = copyImage(it) } }
            catch (_: NotYetAvailableException) { writer.event("image-unavailable", frameId, timestamp, "arcore-camera") }
            try {
                frame.acquireRawDepthImage16Bits().use { raw ->
                    frame.acquireRawDepthConfidenceImage().use { confidence ->
                        if (raw.timestamp != lastDepth && raw.width == confidence.width && raw.height == confidence.height && raw.timestamp == confidence.timestamp) {
                            lastDepth = raw.timestamp
                            depth = DepthSample(raw.width, raw.height, raw.timestamp, copyPlane(raw, 2), copyPlane(confidence, 1))
                        }
                    }
                }
            } catch (_: NotYetAvailableException) {
                writer.event("depth-unavailable", frameId, timestamp, "arcore-camera")
            } catch (_: IllegalStateException) {
                // Depth support is reflected by the actual acquired observations.
            }
        }
        val imageSample = sampled
        val depthSample = depth
        try {
            retention.execute {
                try {
                    if (imageSample != null) {
                        val path = "images/$frameId.jpg"
                        val output = ByteArrayOutputStream()
                        check(YuvImage(imageSample.nv21, ImageFormat.NV21, imageSample.width, imageSample.height, null)
                            .compressToJpeg(Rect(0, 0, imageSample.width, imageSample.height), 95, output)) { "JPEG encoding failed" }
                        writer.writeAsset(path, output.toByteArray())
                        if (abs(imageSample.timestamp - timestamp) <= 2_000_000L) record.put("image", JSONObject()
                            .put("path", path).put("timestampNs", imageSample.timestamp.toString())
                            .put("width", imageSample.width).put("height", imageSample.height))
                        else writer.event("unaligned-image", "$frameId retained $path at ${imageSample.timestamp}", timestamp, "arcore-camera")
                    }
                    if (depthSample != null) {
                        val path = "depth/$frameId.depth16"
                        val confidencePath = "depth/$frameId.confidence8"
                        writer.writeAsset(path, depthSample.depth); writer.writeAsset(confidencePath, depthSample.confidence)
                        if (depthSize.all { it > 0 } && depthFocal.all { it.isFinite() && it > 0 } && depthCenter.all { it.isFinite() }) {
                            val sx = depthSample.width.toDouble() / depthSize[0]
                            val sy = depthSample.height.toDouble() / depthSize[1]
                            record.put("depth", JSONObject().put("path", path).put("confidencePath", confidencePath)
                                .put("timestampNs", depthSample.timestamp.toString()).put("width", depthSample.width).put("height", depthSample.height)
                                .put("fx", depthFocal[0] * sx).put("fy", depthFocal[1] * sy).put("cx", depthCenter[0] * sx).put("cy", depthCenter[1] * sy)
                                .put("stale", abs(depthSample.timestamp - timestamp) > 100_000_000L))
                            if (!depthCalibrationRecorded) {
                                writer.event("depth-calibration", "Raw-depth intrinsics scaled from ARCore texture ${depthSize[0]}x${depthSize[1]} to ${depthSample.width}x${depthSample.height}; JPEG calibration uses the separate CPU image intrinsics", timestamp, "arcore-camera")
                                depthCalibrationRecorded = true
                            }
                            writer.capability("depth", true); writer.capability("confidence", true)
                        } else writer.event("depth-registration-unavailable", "$frameId raw bytes retained at $path and $confidencePath; texture calibration invalid", timestamp, "arcore-camera")
                    }
                    writer.append("frames", record)
                    if ((imageSample != null && abs(imageSample.timestamp - timestamp) <= 2_000_000L || tracking != TrackingState.TRACKING) &&
                        timestamp - lastAnalysis >= 500_000_000L) {
                        lastAnalysis = timestamp
                        analysis.execute {
                            try {
                                val corePose = pose?.let { CameraPose(it.tx(), it.ty(), it.tz(), it.qx(), it.qy(), it.qz(), it.qw()) }
                                val report = analyzer.analyze(frameId, timestamp, imageSample?.luma ?: ByteArray(0),
                                    imageSample?.width ?: 0, imageSample?.height ?: 0, TrackingQuality.valueOf(tracking.name), corePose)
                                val findings = JSONArray()
                                for (finding in report.findings) findings.put(JSONObject().put("code", finding.code)
                                    .put("severity", finding.severity).put("message", finding.message))
                                writer.append("quality", JSONObject().put("frameId", frameId).put("timestampNs", timestamp.toString())
                                    .put("laplacianVariance", report.laplacianVariance ?: JSONObject.NULL)
                                    .put("darkFraction", report.darkFraction ?: JSONObject.NULL).put("brightFraction", report.brightFraction ?: JSONObject.NULL)
                                    .put("overlapEstimate", report.overlapEstimate ?: JSONObject.NULL).put("findings", findings))
                                onGuidance(report.findings.joinToString(" · ") { it.message }.ifEmpty { "Keep a steady sweep. Coverage remains provisional." })
                            } catch (error: Exception) { onError("Quality analysis unavailable: ${error.message}") }
                        }
                    }
                } catch (error: Exception) {
                    runCatching { writer.event("retention-failure", "$frameId: ${error.message}") }
                    onError("Observation save failed: ${error.message}")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            writer.event("dropped-observation", "$frameId sidecar queue full; original MP4 continues", timestamp, "arcore-camera")
            onGuidance("Storage is busy. Slow down; some observation sidecars were dropped.")
        }
    }

    fun stopAccepting() {
        stopped = true
        sensorManager.unregisterListener(this); sensors.quitSafely()
    }

    fun awaitFinished() {
        retention.shutdown()
        while (!retention.awaitTermination(5, TimeUnit.SECONDS)) onGuidance("Saving the remaining observations…")
        analysis.shutdown()
        while (!analysis.awaitTermination(5, TimeUnit.SECONDS)) onGuidance("Saving recorded quality findings…")
        while (sensors.isAlive) sensors.join(1_000)
        writer.flush()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (stopped) return
        try {
            writer.append("imu", JSONObject().put("sensor", if (event.sensor.type == Sensor.TYPE_GYROSCOPE) "gyroscope" else "accelerometer")
                .put("timestampNs", event.timestamp.toString()).put("clockId", "android-elapsed")
                .put("values", JSONArray(event.values.take(3))).put("accuracy", event.accuracy))
        } catch (error: Exception) { onError("IMU save failed: ${error.message}") }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private data class ImageSample(val width: Int, val height: Int, val timestamp: Long, val nv21: ByteArray, val luma: ByteArray)
    private data class DepthSample(val width: Int, val height: Int, val timestamp: Long, val depth: ByteArray, val confidence: ByteArray)

    private fun copyImage(image: Image): ImageSample {
        val width = image.width; val height = image.height
        val luma = copyPlane(image, 1)
        val bytes = ByteArray(width * height + width * height / 2)
        luma.copyInto(bytes)
        val u = image.planes[1]; val v = image.planes[2]
        val ub = u.buffer.duplicate(); val vb = v.buffer.duplicate()
        var offset = width * height
        for (y in 0 until height / 2) for (x in 0 until width / 2) {
            bytes[offset++] = vb.get(vb.position() + y * v.rowStride + x * v.pixelStride)
            bytes[offset++] = ub.get(ub.position() + y * u.rowStride + x * u.pixelStride)
        }
        return ImageSample(width, height, image.timestamp, bytes, luma)
    }

    private fun copyPlane(image: Image, bytesPerPixel: Int): ByteArray {
        val plane = image.planes[0]; val buffer = plane.buffer.duplicate()
        val data = ByteArray(image.width * image.height * bytesPerPixel)
        val base = buffer.position()
        if (plane.pixelStride == bytesPerPixel) {
            val rowBytes = image.width * bytesPerPixel
            for (y in 0 until image.height) {
                buffer.position(base + y * plane.rowStride)
                buffer.get(data, y * rowBytes, rowBytes)
            }
            return data
        }
        var target = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val source = base + y * plane.rowStride + x * plane.pixelStride
            for (b in 0 until bytesPerPixel) data[target++] = buffer.get(source + b)
        }
        return data
    }
}
