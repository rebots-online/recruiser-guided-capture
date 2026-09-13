package mba.robin.recruiser.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PowerManager
import mba.robin.recruiser.core.CameraIntrinsics
import mba.robin.recruiser.core.CameraPose
import mba.robin.recruiser.core.DepthImage
import mba.robin.recruiser.core.DepthObservation
import mba.robin.recruiser.core.RgbImage
import mba.robin.recruiser.core.VoxelFusion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class CaptureReconstruction(private val context: Context, private val directory: File) {
    private val cancelled = AtomicBoolean(false)
    private val projector = VulkanDepthUnprojector()
    val backend: String get() = projector.backend

    fun cancel() { cancelled.set(true) }

    /** Blocking job. The Activity calls this on its single background executor. */
    fun build(worldFrameId: String, onProgress: (String) -> Unit): File {
        require(worldFrameId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid world-frame identity" }
        cancelled.set(false)
        val manifest = JSONObject(File(directory, "manifest.json").readText())
        val calibrations = manifest.getJSONArray("calibrations")
        val calibrationById = (0 until calibrations.length()).associate { index ->
            val value = calibrations.getJSONObject(index)
            value.getString("id") to value
        }
        val framesFile = safeFile(manifest.getJSONObject("streams").getString("frames"))
        onProgress("Checking original observations before reconstruction…")
        val fingerprint = fingerprint(framesFile)
        val checkpointFile = File(directory, "checkpoints/$worldFrameId.checkpoint")
        val restored = if (checkpointFile.exists()) checkpointFile.inputStream().use {
            VoxelFusion.restoreCheckpoint(it, fingerprint, projector)
        } else null
        val fusion = restored?.fusion ?: VoxelFusion(unprojector = projector)
        var nextFrame = restored?.nextFrameIndex ?: 0
        var frameCount = 0
        var accepted = 0
        var capacityReached = false
        var lastVoxelCount = 0
        var stoppedReason: String? = null
        val checkpointCadence = CheckpointCadence(nextFrame)

        fun save() = CapturePackageWriter.atomicWrite(checkpointFile) { fusion.saveCheckpoint(it, nextFrame, fingerprint) }
        fun advance(index: Int) {
            nextFrame = index + 1
            checkpointCadence.completed(nextFrame, ::save)
        }

        try {
            framesFile.bufferedReader().use { lines ->
                while (true) {
                    val text = lines.readLine() ?: break
                    val index = frameCount++
                    if (index < nextFrame) continue
                    if (cancelled.get()) { stoppedReason = "Paused by you. Resume continues from the saved checkpoint."; break }
                    if (Build.VERSION.SDK_INT >= 29 && context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                        stoppedReason = "Phone is hot. Processing is paused with a saved checkpoint; let it cool before resuming."; break
                    }
                    val record = JSONObject(text)
                    if (record.getString("worldFrameId") != worldFrameId || record.getString("tracking") != "TRACKING" ||
                        record.isNull("pose") || record.isNull("depth") || record.getJSONObject("depth").getBoolean("stale")) {
                        advance(index)
                        continue
                    }
                    val observation = readObservation(record, calibrationById)
                    val progress = fusion.integrate(observation)
                    accepted += progress.acceptedSamples
                    lastVoxelCount = progress.voxelCount
                    advance(index)
                    onProgress("${progress.voxelCount} observed points · frame $nextFrame · ${projector.backend}\n" +
                        (projector.fallbackReason?.let { "$it\n" } ?: "") + "Unseen surfaces remain unknown.")
                    if (progress.capacityReached) { capacityReached = true; break }
                    // Yield the reconstruction worker without introducing work into the camera loop.
                    Thread.yield()
                }
            }
        } finally { save() }
        stoppedReason?.let { throw ProcessingPausedException(it) }
        if (accepted == 0 && restored == null) error("No usable depth with tracked pose was recorded in this segment. Camera observations remain saved.")
        val ply = File(directory, "derived/$worldFrameId.ply")
        CapturePackageWriter.atomicWrite(ply) { fusion.writePly(it) }
        // Restored completed jobs may have no new samples; PLY vertex header is the retained count.
        if (lastVoxelCount == 0) ply.useLines { lines ->
            lastVoxelCount = lines.firstOrNull { it.startsWith("element vertex ") }?.substringAfterLast(' ')?.toIntOrNull() ?: 0
        }
        require(lastVoxelCount > 0) { "No valid observed geometry exists in this checkpoint; original capture is unchanged." }
        capacityReached = capacityReached || lastVoxelCount >= fusion.maxVoxels
        val metadata = File(directory, "derived/$worldFrameId.json")
        CapturePackageWriter.atomicWrite(metadata) { output ->
            output.write(JSONObject().put("algorithm", "voxel-fusion-v1").put("inputFingerprint", fingerprint)
                .put("worldFrameId", worldFrameId).put("voxelSizeMeters", fusion.voxelSizeMeters).put("voxelCount", lastVoxelCount)
                .put("capacityReached", capacityReached).put("backend", projector.backend).toString(2).toByteArray())
        }
        CapturePackageWriter(directory).use { writer ->
            val old = writer.manifest.getJSONArray("reconstructions")
            val updated = JSONArray()
            for (i in 0 until old.length()) if (old.getJSONObject(i).getString("worldFrameId") != worldFrameId) updated.put(old.getJSONObject(i))
            updated.put(JSONObject().put("path", "derived/$worldFrameId.ply").put("metadataPath", "derived/$worldFrameId.json").put("worldFrameId", worldFrameId))
            writer.manifest.put("reconstructions", updated); writer.persistManifest()
        }
        onProgress("Saved $lastVoxelCount coloured points locally · ${projector.backend}" +
            if (capacityReached) "\nMap capacity reached; the saved scene is partial." else "\nObserved geometry only; unseen surfaces remain unknown.")
        return ply
    }

    private fun readObservation(record: JSONObject, calibrations: Map<String, JSONObject>): DepthObservation {
        val depth = record.getJSONObject("depth")
        val width = depth.getInt("width"); val height = depth.getInt("height")
        require(width > 0 && height > 0 && width.toLong() * height <= 4_000_000) { "Invalid depth dimensions" }
        val depthBytes = safeFile(depth.getString("path")).readBytes()
        val confidence = safeFile(depth.getString("confidencePath")).readBytes()
        require(depthBytes.size == width * height * 2 && confidence.size == width * height) { "Depth/confidence assets are incomplete" }
        val samples = ShortArray(width * height)
        ByteBuffer.wrap(depthBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val depthIntrinsics = intrinsics(depth, width, height)
        val transform = record.getJSONObject("pose")
        val p = transform.getJSONArray("position"); val q = transform.getJSONArray("quaternion")
        val pose = CameraPose(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat(),
            q.getDouble(0).toFloat(), q.getDouble(1).toFloat(), q.getDouble(2).toFloat(), q.getDouble(3).toFloat())
        var color: RgbImage? = null
        if (!record.isNull("image")) {
            val image = record.getJSONObject("image")
            val calibration = calibrations[record.getString("calibrationId")] ?: error("Frame calibration is missing")
            val options = BitmapFactory.Options().apply { inScaled = false }
            val bitmap = BitmapFactory.decodeFile(safeFile(image.getString("path")).path, options) ?: error("Saved camera image cannot be decoded")
            try {
                require(bitmap.width == calibration.getInt("width") && bitmap.height == calibration.getInt("height")) { "Image does not match its saved calibration" }
                require(bitmap.width.toLong() * bitmap.height <= 4_000_000) { "Image dimensions exceed the reconstruction bound" }
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val rgb = ByteArray(pixels.size * 3)
                for (index in pixels.indices) {
                    rgb[index * 3] = (pixels[index] shr 16).toByte(); rgb[index * 3 + 1] = (pixels[index] shr 8).toByte(); rgb[index * 3 + 2] = pixels[index].toByte()
                }
                color = RgbImage(bitmap.width, bitmap.height, rgb, intrinsics(calibration, bitmap.width, bitmap.height))
            } finally { bitmap.recycle() }
        }
        return DepthObservation(record.getString("id"), record.getString("worldFrameId"), record.getString("timestampNs").toLong(),
            pose, DepthImage(width, height, samples, confidence, depthIntrinsics), color)
    }

    private fun intrinsics(value: JSONObject, width: Int, height: Int) = CameraIntrinsics(width, height,
        value.getDouble("fx").toFloat(), value.getDouble("fy").toFloat(), value.getDouble("cx").toFloat(), value.getDouble("cy").toFloat())

    private fun safeFile(path: String): File {
        require(!path.startsWith('/') && !path.contains('\\') && path.split('/').none { it == ".." }) { "Invalid capture asset path" }
        val file = File(directory, path).canonicalFile
        require(file.path.startsWith(directory.canonicalPath + File.separator) && file.isFile) { "Missing capture asset: $path" }
        return file
    }

    private fun fingerprint(framesFile: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        framesFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val originals = listOf("originals", "images", "depth").flatMap { name -> File(directory, name).walkTopDown().filter { it.isFile && !it.name.endsWith(".partial") }.toList() }
        for (file in originals.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }) {
            if (cancelled.get()) throw ProcessingPausedException("Processing paused while checking originals.")
            digest.update(CapturePackageWriter.sha256(file).toByteArray(Charsets.US_ASCII))
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    class ProcessingPausedException(message: String) : Exception(message)
}

/** Counts completed input records, including records with no usable depth. Failed saves remain due. */
internal class CheckpointCadence(private var savedNextFrame: Int, private val interval: Int = 25) {
    init { require(savedNextFrame >= 0 && interval > 0) }
    fun completed(nextFrame: Int, save: () -> Unit) {
        require(nextFrame >= savedNextFrame) { "Checkpoint position cannot move backward" }
        if (nextFrame - savedNextFrame >= interval) {
            save()
            savedNextFrame = nextFrame
        }
    }
}
