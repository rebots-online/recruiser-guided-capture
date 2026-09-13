package mba.robin.recruiser.capture

import android.app.Activity
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.RecordingConfig
import com.google.ar.core.Session
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Every ARCore transition, update and GL operation is serialized on the surface thread. */
class ArCoreCaptureController(
    private val activity: Activity,
    private val surface: GLSurfaceView,
    private val onStatus: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val onSaved: (File) -> Unit,
    private val existingDirectory: File? = null,
) : GLSurfaceView.Renderer {
    private var session: Session? = null
    private var writer: CapturePackageWriter? = null
    private var recorder: SessionObservationRecorder? = null
    private var segment: JSONObject? = null
    private val background = CameraBackground()
    private var width = 1
    private var height = 1
    private var lastTimestamp = 0L
    private var lastStatusTimestamp = 0L
    private var playback = false
    private var errorReported = false
    private val finalizer = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    @Volatile var captureDirectory: File? = existingDirectory
        private set
    @Volatile var state = "preview"
        private set

    init {
        surface.setEGLContextClientVersion(2)
        surface.preserveEGLContextOnPause = true
        surface.setRenderer(this)
    }

    fun openPreview() = execute {
        ensureSession()
        changeState("preview")
    }

    fun start() = execute {
        if (state != "preview" && state != "paused") return@execute
        if (playback) { destroySession(); playback = false }
        val active = ensureSession()
        val output = writer ?: (existingDirectory?.let { CapturePackageWriter(it) }
            ?: CapturePackageWriter.create(File(activity.filesDir, "captures"))).also { writer = it }
        captureDirectory = output.directory
        val size = active.cameraConfig.imageSize
        val current = output.beginSegment(size.width, size.height)
        val recording = RecordingConfig(active).setMp4DatasetUri(Uri.fromFile(File(output.directory, current.getString("path"))))
            .setAutoStopOnPause(true).setRecordingRotation(0)
        try { active.startRecording(recording) }
        catch (error: Exception) {
            output.event("recording-start-failed", error.message ?: error.javaClass.simpleName)
            output.abandonUnstartedSegment(current)
            throw error
        }
        segment = current
        recorder = SessionObservationRecorder(activity, output, current, active.cameraConfig.cameraId,
            { message -> activity.runOnUiThread { onStatus(message) } },
            { message -> activity.runOnUiThread { onStatus(message) } })
        output.event("start", "Segment ${current.getString("id")}; selected CPU resolution ${size.width}×${size.height}")
        lastTimestamp = 0L; errorReported = false
        changeState("recording")
    }

    fun pause() = execute {
        if (state != "recording") return@execute
        stopSegment("pause"); destroySession(); changeState("paused")
    }

    fun resume() = start()

    fun finish() = execute {
        if (state == "saving" || state == "saved") return@execute
        stopSegment("finish"); destroySession()
        writer?.let { saved ->
            writer = null
            changeState("saving")
            finalizer.execute {
                try {
                    saved.finish(); saved.close(); changeState("saved")
                    activity.runOnUiThread { onSaved(saved.directory) }
                } catch (error: Exception) { report(error) }
            }
        } ?: changeState("preview")
    }

    fun playback(segment: File) = execute {
        check(recorder == null) { "Finish capture before replaying a saved segment." }
        destroySession()
        val active = Session(activity)
        try {
            active.setPlaybackDatasetUri(Uri.fromFile(segment))
            active.setCameraTextureName(background.texture)
            active.resume(); session = active; playback = true
            changeState("playback")
        } catch (error: Exception) { active.close(); throw error }
    }

    /** Returns only after the camera has been released, before Activity pauses its GL surface. */
    fun suspendForBackground() {
        val completed = CountDownLatch(1)
        surface.queueEvent {
            try {
                stopSegment("interruption"); destroySession()
                if (writer != null) changeState("paused")
            } catch (error: Exception) { report(error) } finally { completed.countDown() }
        }
        completed.await(5, TimeUnit.SECONDS)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val completed = CountDownLatch(1)
        surface.queueEvent {
            try {
                stopSegment("interruption"); destroySession()
                val saved = writer; writer = null
                finalizer.execute { saved?.close() }
                finalizer.shutdown()
            }
            catch (error: Exception) { report(error) } finally { completed.countDown() }
        }
        completed.await(5, TimeUnit.SECONDS)
    }

    /** suspendForBackground + GLSurfaceView.onPause have already released the session. */
    fun closeAfterSurfacePause() {
        if (!closed.compareAndSet(false, true)) return
        val saved = writer; writer = null
        finalizer.execute { saved?.close() }
        finalizer.shutdown()
    }

    private fun stopSegment(reason: String) {
        val pending = recorder ?: return
        pending.stopAccepting()
        val output = writer ?: return
        val current = segment
        val timestamp = lastTimestamp
        var finalized = true
        try { session?.stopRecording() } catch (error: Exception) {
            finalized = false; writer?.event("recording-finalize-failed", error.message ?: error.javaClass.simpleName)
        }
        recorder = null; segment = null
        // Release the camera before draining encoding/fsync work off the GL and UI threads.
        destroySession()
        finalizer.execute {
            try {
                pending.awaitFinished()
                current?.let { output.endSegment(it, timestamp, finalized) }
                output.event(reason, "Recording stopped; a resumed segment uses an independent world frame")
            } catch (error: Exception) { report(error) }
        }
    }

    private fun ensureSession(): Session {
        session?.let { return it }
        val active = Session(activity)
        try {
            val configurations = active.getSupportedCameraConfigs(CameraConfigFilter(active)
                .setFacingDirection(CameraConfig.FacingDirection.BACK))
            val bounded = configurations.filter { it.imageSize.width <= 1920 && it.imageSize.height <= 1080 }
            val selected = (bounded.ifEmpty { configurations }).maxByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
                ?: error("ARCore has no supported rear-camera configuration")
            active.cameraConfig = selected
            val configuration = Config(active).apply {
                focusMode = Config.FocusMode.AUTO
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                depthMode = if (active.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) Config.DepthMode.RAW_DEPTH_ONLY else Config.DepthMode.DISABLED
            }
            active.configure(configuration)
            if (background.texture != 0) active.setCameraTextureName(background.texture)
            active.resume(); session = active; playback = false
            activity.runOnUiThread { onStatus("Rear camera ${selected.imageSize.width}×${selected.imageSize.height} · " +
                if (configuration.depthMode == Config.DepthMode.DISABLED) "Depth unavailable; camera and tracking can still be saved." else "Depth enabled; move gently to establish tracking.") }
            return active
        } catch (error: Exception) { active.close(); throw error }
    }

    private fun destroySession() {
        session?.let { active -> try { active.pause() } finally { active.close() } }
        session = null; playback = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        background.create()
        session?.setCameraTextureName(background.texture)
        GLES20.glClearColor(0.035f, 0.055f, 0.075f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width; this.height = height; GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val active = session ?: return
        try {
            @Suppress("DEPRECATION") val rotation = activity.windowManager.defaultDisplay.rotation
            active.setDisplayGeometry(rotation, width, height)
            active.setCameraTextureName(background.texture)
            val frame = active.update()
            background.draw(frame)
            if (recorder != null) {
                recorder?.record(frame)
                lastTimestamp = frame.timestamp
            } else if (playback && active.playbackStatus == PlaybackStatus.FINISHED) {
                destroySession(); changeState("playback-finished")
            } else if (frame.timestamp - lastStatusTimestamp > 2_000_000_000 && state == "preview") {
                lastStatusTimestamp = frame.timestamp
                activity.runOnUiThread { onStatus("${frame.camera.trackingState} · Ready to start capture. Audio is off.") }
            }
        } catch (error: Exception) {
            if (!errorReported) {
                errorReported = true
                runCatching { stopSegment("interruption"); destroySession() }
                changeState("paused"); report(error)
            }
        }
    }

    private fun execute(block: () -> Unit) = surface.queueEvent {
        if (!closed.get()) try { block() } catch (error: Exception) { report(error) }
    }

    private fun report(error: Exception) = activity.runOnUiThread {
        onStatus("${error.javaClass.simpleName}: ${error.message ?: "Capture operation failed; saved observations are retained."}")
    }

    private fun changeState(next: String) {
        state = next; activity.runOnUiThread { onState(next) }
    }
}
