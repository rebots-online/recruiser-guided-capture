package mba.robin.recruiser.capture

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class CaptureActivity : Activity() {
    private lateinit var root: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private var controller: ArCoreCaptureController? = null
    private var cameraView: GLSurfaceView? = null
    private var pointView: PointCloudView? = null
    private var selectedDirectory: File? = null
    private var exportDirectory: File? = null
    private var reconstruction: CaptureReconstruction? = null
    private var pendingCamera = false
    private var installRequested = false
    private var activityPaused = false
    private var reviewAfterResume: File? = null
    private val captures get() = File(filesDir, "captures")
    private val graphite = Color.rgb(13, 23, 30)
    private val teal = Color.rgb(111, 225, 210)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(graphite) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left + dp(12), bars.top + dp(8), bars.right + dp(12), bars.bottom + dp(8))
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft + dp(12), insets.systemWindowInsetTop + dp(8),
                    insets.systemWindowInsetRight + dp(12), insets.systemWindowInsetBottom + dp(8))
            }
            insets
        }
        setContentView(root)
        captures.mkdirs()
        val savedId = savedInstanceState?.getString("sessionId")
        selectedDirectory = savedId?.takeIf { it.matches(Regex("[A-Za-z0-9-]+")) }?.let { File(captures, it) }
        exportDirectory = savedInstanceState?.getString("exportSessionId")?.takeIf { it.matches(Regex("[A-Za-z0-9-]+")) }?.let { File(captures, it) }
        showHome()
        io.execute {
            try {
                CapturePackageWriter.recover(captures)
                runOnUiThread { if (controller == null && !isFinishing) selectedDirectory?.let { showReview(it) } ?: showHome() }
            } catch (error: Exception) { runOnUiThread { errorDialog("Capture recovery", error) } }
        }
    }

    private fun clearScreen() {
        if (activityPaused) controller?.closeAfterSurfacePause() else controller?.close()
        controller = null
        cameraView?.onPause(); cameraView = null
        pointView?.onPause(); pointView = null
        root.removeAllViews()
    }

    private fun heading(text: String) = label(text, 25f).also { it.setTextColor(teal); root.addView(it) }

    private fun showHome() {
        clearScreen(); heading("Recruiser")
        root.addView(label("Capture your surroundings now. Build and revisit the room on this phone afterward."))
        root.addView(button("Capture surroundings") { selectedDirectory = null; requestCapture() })
        root.addView(label("Saved on this device · no reconstruction server needed · audio is off", 13f))
        val scroll = ScrollView(this); val list = column()
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val sessions = captures.listFiles()?.filter { File(it, "manifest.json").isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (sessions.isEmpty()) list.addView(label("Your saved captures will appear here."))
        for (directory in sessions) {
            try {
                val manifest = JSONObject(File(directory, "manifest.json").readText())
                list.addView(button("${manifest.getString("createdAt").replace('T', ' ').take(19)}\n${manifest.getJSONArray("segments").length()} segments · ${manifest.getString("state")}") {
                    selectedDirectory = directory; showReview(directory)
                })
            } catch (error: Exception) { list.addView(label("Unreadable saved session ${directory.name.take(8)}: ${error.message}")) }
        }
    }

    private fun requestCapture() {
        if (reconstruction != null) return
        pendingCamera = true
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10); return
        }
        try {
            if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true; return
            }
            pendingCamera = false; showCapture(selectedDirectory)
        } catch (error: Exception) { pendingCamera = false; errorDialog("Camera capture unavailable", error) }
    }

    private fun showCapture(existing: File?) {
        clearScreen(); heading("Capture surroundings")
        val status = label("Opening the rear-camera viewfinder…", 14f)
        root.addView(status)
        val elapsed = label("Ready · recordings stay on this phone", 13f)
        root.addView(elapsed)
        var segmentStarted = 0L
        val preview = GLSurfaceView(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f)); cameraView = preview
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = button("Start capture") { controller?.start() }
        val pause = button("Pause") { controller?.pause() }.apply { isEnabled = false }
        val finish = button("Finish") { controller?.finish() }.apply { isEnabled = false }
        for (control in listOf(start, pause, finish)) controlRow.addView(control, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controlRow)
        root.addView(button("Back to saved captures") {
            if (controller?.state == "recording" || controller?.state == "paused") controller?.finish() else showHome()
        })
        root.addView(label("Quality and overlap cues are provisional. Unknown surfaces are never marked complete.", 12f))
        controller = ArCoreCaptureController(this, preview, { status.text = it }, { state ->
            if (state == "recording") segmentStarted = SystemClock.elapsedRealtime()
            if (state != "recording") elapsed.text = when (state) {
                "saving" -> "Saving final observations…"
                "paused" -> "Paused · observations retained locally"
                "saved" -> "Saved on this phone"
                else -> "Ready · recordings stay on this phone"
            }
            start.text = if (state == "paused") "Resume" else "Start capture"
            start.isEnabled = state == "preview" || state == "paused"
            pause.isEnabled = state == "recording"
            finish.isEnabled = state == "recording" || state == "paused"
            if (state == "paused") status.text = "Paused. Saved observations remain on this phone; Resume begins a new coordinate frame."
        }, { directory ->
            selectedDirectory = directory
            if (activityPaused) reviewAfterResume = directory else showReview(directory)
        }, existing)
        elapsed.post(object : Runnable {
            override fun run() {
                if (cameraView !== preview || isDestroyed) return
                if (controller?.state == "recording") {
                    val seconds = (SystemClock.elapsedRealtime() - segmentStarted) / 1000
                    elapsed.text = "Recording segment · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} · saved locally"
                }
                elapsed.postDelayed(this, 500)
            }
        })
        preview.onResume(); controller?.openPreview()
    }

    private fun showReview(directory: File) {
        clearScreen(); selectedDirectory = directory; heading("Saved capture")
        val manifest = try { JSONObject(File(directory, "manifest.json").readText()) }
        catch (error: Exception) { errorDialog("Read saved capture", error); showHome(); return }
        val status = label("${manifest.getString("createdAt")} · ${manifest.getString("state")}", 14f)
        root.addView(status)
        val scroll = ScrollView(this); val contents = column()
        scroll.addView(contents); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val caps = manifest.getJSONObject("capabilities")
        contents.addView(label("Camera: ${caps.getBoolean("camera")} · tracked poses: ${caps.getBoolean("pose")}\n" +
            "Depth: ${caps.getBoolean("depth")} · IMU: ${caps.getBoolean("imu")} · ${manifest.getJSONArray("calibrations").length()} calibrations", 14f))
        val imuClock = manifest.getJSONArray("clocks").getJSONObject(1)
        if (imuClock.isNull("offsetToSessionNs")) contents.addView(label("IMU timing is retained but not proven aligned with camera images.", 14f))
        val segments = manifest.getJSONArray("segments")
        for (i in 0 until segments.length()) {
            val segment = segments.getJSONObject(i)
            contents.addView(button("Replay segment ${i + 1} · ${segment.getInt("width")}×${segment.getInt("height")} · ${segment.getString("state")}") {
                showReplay(directory, File(directory, segment.getString("path")))
            })
        }
        val images = File(directory, "images").listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.name }?.take(6).orEmpty()
        if (images.isNotEmpty()) {
            contents.addView(label("Retained camera views", 17f))
            for (image in images) {
                val preview = ImageView(this).apply {
                    adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Retained camera observation ${image.name}"
                    setImageBitmap(decodePreview(image, 480))
                    setOnClickListener {
                        val large = ImageView(this@CaptureActivity).apply { setImageBitmap(decodePreview(image, 1280)); adjustViewBounds = true }
                        AlertDialog.Builder(this@CaptureActivity).setTitle("Recorded view").setView(large).setPositiveButton("Close", null).show()
                    }
                }
                contents.addView(preview, LinearLayout.LayoutParams(-1, dp(160)))
            }
        }
        val qualityLabel = label("Loading recorded guidance…", 14f); contents.addView(qualityLabel)
        io.execute {
            try {
                val findings = linkedSetOf<String>()
                var frames = 0; var sensorSamples = 0; var gaps = 0
                File(directory, "observations/frames.jsonl").forEachLine { if (it.isNotBlank()) frames++ }
                File(directory, "observations/imu.jsonl").forEachLine { if (it.isNotBlank()) sensorSamples++ }
                File(directory, "observations/events.jsonl").forEachLine {
                    if (it.isNotBlank() && JSONObject(it).getString("type") in listOf("interruption", "retention-failure", "dropped-observation", "pause")) gaps++
                }
                File(directory, "observations/quality.jsonl").forEachLine { line ->
                    if (line.isNotBlank()) {
                        val records = JSONObject(line).getJSONArray("findings")
                        for (i in 0 until records.length()) if (findings.size < 12) findings.add(records.getJSONObject(i).getString("message"))
                    }
                }
                runOnUiThread { qualityLabel.text = "$frames frame observations · $sensorSamples IMU samples · $gaps recorded gaps/events\n" +
                    findings.joinToString("\n").ifEmpty { "No recorded quality warning; this is not proof of complete coverage." } }
            } catch (error: Exception) { runOnUiThread { qualityLabel.text = "Some sidecars could not be read: ${error.message}" } }
        }
        contents.addView(button("Build 3D on this device / Resume") { chooseWorld(directory, status) })
        contents.addView(button("Pause processing") { reconstruction?.cancel() })
        val results = manifest.getJSONArray("reconstructions")
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            contents.addView(button("Revisit saved 3D · scan ${i + 1}") { showPointCloud(directory, File(directory, result.getString("path"))) })
        }
        contents.addView(button("Export capture package") {
            if (reconstruction != null) { status.text = "Pause processing before exporting a consistent capture package."; return@button }
            exportDirectory = directory
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "${directory.name}.recruiser-capture.zip")
            }, 20)
        })
        contents.addView(button("Continue scanning") { if (reconstruction == null) requestCapture() })
        root.addView(button("All saved captures") { if (reconstruction == null) showHome() })
    }

    private fun chooseWorld(directory: File, status: TextView) {
        if (reconstruction != null) return
        val segments = JSONObject(File(directory, "manifest.json").readText()).getJSONArray("segments")
        if (segments.length() == 0) { status.text = "No recorded segment is available."; return }
        val worlds = (0 until segments.length()).map { segments.getJSONObject(it).getString("worldFrameId") }.distinct()
        fun begin(world: String) {
            val job = CaptureReconstruction(this, directory); reconstruction = job
            status.text = "Preparing local reconstruction…"
            io.execute {
                try {
                    val file = job.build(world) { message -> runOnUiThread { status.text = message } }
                    runOnUiThread {
                        reconstruction = null
                        if (activityPaused) reviewAfterResume = directory else showPointCloud(directory, file)
                    }
                } catch (error: Exception) {
                    runOnUiThread { reconstruction = null; status.text = error.message ?: "Processing failed; originals are retained." }
                }
            }
        }
        if (worlds.size == 1) begin(worlds.first())
        else AlertDialog.Builder(this).setTitle("Choose a scan coordinate frame")
            .setItems(worlds.mapIndexed { index, _ -> "Scan ${index + 1} · independent origin" }.toTypedArray()) { _, index -> begin(worlds[index]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showReplay(directory: File, segment: File) {
        clearScreen(); heading("Recorded camera replay")
        val status = label("Opening saved ARCore dataset…", 14f); root.addView(status)
        val surface = GLSurfaceView(this); cameraView = surface
        root.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button("Back to saved capture") { showReview(directory) })
        controller = ArCoreCaptureController(this, surface, { status.text = it }, { state ->
            if (state == "playback-finished") status.text = "Replay finished. Recorded observations remain unchanged."
        }, {}, null)
        surface.onResume(); controller?.playback(segment)
    }

    private fun showPointCloud(directory: File, file: File) {
        clearScreen(); heading("Your saved room")
        val status = label("Loading observed geometry…", 14f); root.addView(status)
        val view = PointCloudView(this); pointView = view
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f)); view.onResume()
        root.addView(label("Drag to orbit · pinch to zoom · two fingers to pan\nHoles and unseen surfaces remain visible; this is observed geometry.", 13f))
        root.addView(button("Back to capture") { showReview(directory) })
        io.execute {
            try { val count = view.load(file); runOnUiThread { status.text = "$count saved points · local to this phone" } }
            catch (error: Exception) { runOnUiThread { status.text = "Saved scene could not be opened: ${error.message}" } }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return; val directory = exportDirectory ?: return
            io.execute {
                try {
                    val output = contentResolver.openOutputStream(uri, "w") ?: error("Could not open selected export destination")
                    CapturePackageWriter(directory).use { it.exportArchive(output) }
                    runOnUiThread { AlertDialog.Builder(this).setMessage("Capture package exported. Originals remain saved on this phone.").setPositiveButton("OK", null).show() }
                } catch (error: Exception) { runOnUiThread { errorDialog("Export incomplete; originals are retained", error) } }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestCapture()
            else { pendingCamera = false; AlertDialog.Builder(this).setMessage("Camera permission is needed for the live viewfinder. Saved captures remain available.").setPositiveButton("OK", null).show() }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        (controller?.captureDirectory ?: selectedDirectory)?.let { outState.putString("sessionId", it.name) }
        exportDirectory?.let { outState.putString("exportSessionId", it.name) }
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        controller?.suspendForBackground(); cameraView?.onPause(); pointView?.onPause()
        reconstruction?.cancel(); activityPaused = true
        super.onPause()
    }
    override fun onResume() {
        super.onResume(); activityPaused = false
        cameraView?.onResume(); pointView?.onResume()
        reviewAfterResume?.let { directory -> reviewAfterResume = null; showReview(directory) }
        if (pendingCamera && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) requestCapture()
    }
    override fun onDestroy() {
        reconstruction?.cancel()
        if (activityPaused) controller?.closeAfterSurfacePause() else controller?.close()
        io.shutdown(); super.onDestroy()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(209, 229, 235)); setPadding(0, dp(8), 0, dp(8))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; minHeight = dp(48); gravity = Gravity.CENTER; setTextColor(graphite)
        backgroundTintList = android.content.res.ColorStateList.valueOf(teal)
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun errorDialog(title: String, error: Exception) {
        AlertDialog.Builder(this).setTitle(title).setMessage(error.message ?: error.javaClass.simpleName).setPositiveButton("OK", null).show()
    }
    private fun decodePreview(file: File, maximum: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maximum) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false })
    }
}
