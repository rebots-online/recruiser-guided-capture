package mba.robin.recruiser.capture

import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Private session directory is the journal; exporting never moves its observations. */
class CapturePackageWriter(
    val directory: File,
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val elapsedNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) : AutoCloseable {
    private val journals = mutableMapOf<String, Pair<FileOutputStream, BufferedWriter>>()
    private var lastSync = 0L
    val manifest: JSONObject

    init {
        directory.mkdirs()
        val existing = File(directory, "manifest.json")
        manifest = if (existing.exists()) JSONObject(existing.readText()) else freshManifest()
        for (name in listOf("frames", "imu", "events", "quality")) {
            val file = File(directory, "observations/$name.jsonl")
            file.parentFile!!.mkdirs()
            if (!file.exists()) file.createNewFile()
        }
        persistManifest()
    }

    private fun freshManifest() = JSONObject().apply {
        put("format", "recruiser.capture"); put("version", 1)
        put("sessionId", directory.name); put("createdAt", Instant.now().toString())
        put("state", "interrupted")
        put("provider", JSONObject().put("name", "arcore-android").put("version", "1.56.0")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("androidApi", Build.VERSION.SDK_INT))
        put("coordinateSystem", JSONObject().put("handedness", "right").put("up", "+Y")
            .put("forward", "-Z").put("units", "meters"))
        put("capabilities", JSONObject().put("camera", false).put("pose", false).put("imu", false)
            .put("depth", false).put("confidence", false).put("location", false))
        put("clocks", JSONArray().put(clock("arcore-camera")).put(clock("android-elapsed")))
        put("segments", JSONArray()); put("calibrations", JSONArray()); put("assets", JSONArray())
        put("reconstructions", JSONArray())
        put("streams", JSONObject().apply {
            for (name in listOf("frames", "imu", "events", "quality")) put(name, "observations/$name.jsonl")
        })
    }

    private fun clock(id: String) = JSONObject().put("id", id).put("unit", "nanoseconds")
        .put("offsetToSessionNs", JSONObject.NULL).put("uncertaintyNs", JSONObject.NULL)
        .put("source", "Unaligned until camera timestamp source is established")

    @Synchronized fun beginSegment(width: Int, height: Int): JSONObject {
        check(directory.usableSpace > 128L * 1024 * 1024) { "Less than 128 MiB free; saved observations are retained." }
        val id = UUID.randomUUID().toString()
        val result = JSONObject().put("id", id).put("path", "originals/$id.mp4")
            .put("worldFrameId", UUID.randomUUID().toString()).put("startTimestampNs", "0")
            .put("endTimestampNs", JSONObject.NULL).put("state", "interrupted")
            .put("mime", "video/mp4").put("width", width).put("height", height)
        File(directory, result.getString("path")).parentFile!!.mkdirs()
        manifest.getJSONArray("segments").put(result)
        manifest.put("state", "interrupted")
        persistManifest()
        return result
    }

    @Synchronized fun abandonUnstartedSegment(segment: JSONObject) {
        check(segment.getString("startTimestampNs") == "0") { "Cannot abandon a segment containing observations" }
        val source = File(directory, segment.getString("path"))
        if (!source.exists() || source.length() == 0L) {
            val segments = manifest.getJSONArray("segments")
            for (index in segments.length() - 1 downTo 0) {
                if (segments.getJSONObject(index).getString("id") == segment.getString("id")) segments.remove(index)
            }
            event("recording-start-abandoned", "Segment ${segment.getString("id")} never started; no recorded media was discarded")
        } else {
            segment.put("state", "interrupted")
            event("recording-start-failed", "Segment ${segment.getString("id")} retained its interrupted original MP4")
        }
        persistManifest()
    }

    @Synchronized fun establishClock(timestamp: Long, sharedElapsedClock: Boolean) {
        val clocks = manifest.getJSONArray("clocks")
        val camera = clocks.getJSONObject(0)
        if (camera.isNull("offsetToSessionNs")) {
            camera.put("offsetToSessionNs", (-timestamp).toString()).put("uncertaintyNs", "0")
                .put("source", "ARCore camera timestamp minus first retained camera timestamp")
            clocks.getJSONObject(1).apply {
                put("offsetToSessionNs", if (sharedElapsedClock) (-timestamp).toString() else JSONObject.NULL)
                put("uncertaintyNs", if (sharedElapsedClock) "0" else JSONObject.NULL)
                put("source", if (sharedElapsedClock) "Camera timestamp source REALTIME and Android SensorEvent elapsed realtime"
                    else "Camera timestamp source unknown; IMU retained without image alignment")
            }
            persistManifest()
        }
    }

    @Synchronized fun startSegmentClock(segment: JSONObject, timestamp: Long, sharedElapsedClock: Boolean) {
        if (segment.getString("startTimestampNs") == "0") {
            segment.put("startTimestampNs", timestamp.toString())
            establishClock(timestamp, sharedElapsedClock)
            persistManifest()
        }
    }

    @Synchronized fun calibration(cameraId: String, width: Int, height: Int, focal: FloatArray, center: FloatArray): String {
        val entries = manifest.getJSONArray("calibrations")
        for (i in 0 until entries.length()) {
            val old = entries.getJSONObject(i)
            if (old.getString("cameraId") == cameraId && old.getInt("width") == width && old.getInt("height") == height &&
                old.getDouble("fx").toFloat() == focal[0] && old.getDouble("fy").toFloat() == focal[1] &&
                old.getDouble("cx").toFloat() == center[0] && old.getDouble("cy").toFloat() == center[1]) return old.getString("id")
        }
        val id = "calibration-${entries.length()}"
        entries.put(JSONObject().put("id", id).put("cameraId", cameraId).put("width", width).put("height", height)
            .put("fx", focal[0]).put("fy", focal[1]).put("cx", center[0]).put("cy", center[1])
            .put("distortion", JSONObject.NULL).put("crop", JSONObject.NULL).put("rotationDegrees", 0).put("lensId", JSONObject.NULL))
        persistManifest()
        return id
    }

    @Synchronized fun append(stream: String, record: JSONObject) {
        check(stream in listOf("frames", "imu", "events", "quality"))
        val pair = journals.getOrPut(stream) {
            val output = FileOutputStream(File(directory, "observations/$stream.jsonl"), true)
            output to BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
        }
        pair.second.write(record.toString()); pair.second.newLine(); pair.second.flush()
        if (elapsedMillis() - lastSync > 1000) flush()
    }

    @Synchronized fun event(type: String, detail: String, timestamp: Long = elapsedNanos(), clock: String = "android-elapsed") {
        append("events", JSONObject().put("timestampNs", timestamp.toString()).put("clockId", clock)
            .put("type", type).put("detail", detail))
    }

    @Synchronized fun capability(name: String, available: Boolean) {
        manifest.getJSONObject("capabilities").put(name, available)
    }

    fun writeAsset(path: String, bytes: ByteArray): File {
        require(!path.contains("..") && !path.startsWith("/") && !path.contains('\\'))
        val file = File(directory, path)
        file.parentFile!!.mkdirs()
        atomicWrite(file) { it.write(bytes) }
        return file
    }

    @Synchronized fun endSegment(segment: JSONObject, timestamp: Long, finalized: Boolean) {
        segment.put("endTimestampNs", timestamp.toString()).put("state", if (finalized) "finalized" else "interrupted")
        flush(); persistManifest()
    }

    @Synchronized fun finish() {
        manifest.put("state", if ((0 until manifest.getJSONArray("segments").length()).all {
                manifest.getJSONArray("segments").getJSONObject(it).getString("state") == "finalized"
            }) "finalized" else "interrupted")
        flush(); persistManifest()
    }

    @Synchronized fun persistManifest() {
        atomicWrite(File(directory, "manifest.json")) { it.write(manifest.toString(2).toByteArray()) }
    }

    @Synchronized fun flush() {
        for ((output, writer) in journals.values) { writer.flush(); output.fd.sync() }
        lastSync = elapsedMillis()
    }

    @Synchronized override fun close() {
        flush()
        for ((_, writer) in journals.values) writer.close()
        journals.clear()
    }

    @Synchronized fun exportArchive(destination: OutputStream) {
        flush()
        val files = listOf("originals", "images", "depth", "observations", "derived").flatMap { folder ->
            File(directory, folder).walkTopDown().filter { it.isFile && !it.name.endsWith(".partial") }.toList()
        }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
        require(files.size + 1 <= 100_000) { "Package has too many entries; originals remain saved." }
        require(files.sumOf { it.length() } <= MAX_BYTES) { "Package exceeds 2 GiB; originals remain saved." }
        val assets = JSONArray()
        for (file in files) assets.put(JSONObject().put("path", file.relativeTo(directory).invariantSeparatorsPath)
            .put("size", file.length()).put("sha256", sha256(file)).put("mime", mime(file)))
        val exported = JSONObject(manifest.toString()).put("assets", assets)
        val manifestBytes = exported.toString(2).toByteArray()
        require(files.sumOf { it.length() } + manifestBytes.size <= MAX_BYTES) { "Package exceeds 2 GiB." }
        ZipOutputStream(destination).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifestBytes); zip.closeEntry()
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024

        fun create(parent: File) = CapturePackageWriter(File(parent, UUID.randomUUID().toString()))

        fun atomicWrite(file: File, block: (FileOutputStream) -> Unit) {
            file.parentFile!!.mkdirs()
            val pending = File(file.parentFile, file.name + ".partial")
            FileOutputStream(pending).use { block(it); it.flush(); it.fd.sync() }
            check(pending.renameTo(file)) { "Could not commit ${file.name}; partial file retained." }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        private fun mime(file: File) = when (file.extension) {
            "jpg" -> "image/jpeg"; "mp4" -> "video/mp4"; "json" -> "application/json"
            "jsonl" -> "application/x-ndjson"; "ply" -> "application/ply"; else -> "application/octet-stream"
        }

        fun recover(parent: File): List<File> = parent.listFiles()?.filter { File(it, "manifest.json").isFile }?.onEach { dir ->
            val repairs = mutableListOf<String>()
            for (name in listOf("frames", "imu", "events", "quality")) {
                val file = File(dir, "observations/$name.jsonl")
                recoverJournal(file)?.let { repairs.add(it) }
            }
            if (repairs.isNotEmpty()) CapturePackageWriter(dir).use { writer ->
                writer.manifest.put("state", "interrupted")
                writer.event("recovery", repairs.joinToString("; "))
                writer.persistManifest()
            }
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        /** Keep complete EOF records, repair their separator, and back up any bytes before changing them. */
        internal fun recoverJournal(file: File): String? {
            if (!file.isFile) return null
            var validEnd = 0L
            var needsSeparator = false
            RandomAccessFile(file, "r").use { input ->
                while (input.filePointer < input.length()) {
                    val line = input.readLine() ?: break
                    try {
                        val utf8 = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(line.toByteArray(Charsets.ISO_8859_1))).toString()
                        val tokens = JSONTokener(utf8)
                        JSONObject(tokens)
                        require(tokens.nextClean() == '\u0000') { "Trailing content after journal record" }
                    } catch (_: Exception) { break }
                    validEnd = input.filePointer
                    input.seek(validEnd - 1)
                    val lastByte = input.read()
                    input.seek(validEnd)
                    if (lastByte != '\n'.code && lastByte != '\r'.code) {
                        needsSeparator = true
                        break
                    }
                }
            }
            if (validEnd == file.length() && !needsSeparator) return null
            val backup = File(file.parentFile!!.parentFile, "recovery/${file.nameWithoutExtension}-${UUID.randomUUID()}.jsonl")
            atomicWrite(backup) { output -> file.inputStream().use { it.copyTo(output) } }
            val discarded = file.length() - validEnd
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(validEnd)
                if (needsSeparator) { output.seek(validEnd); output.write('\n'.code) }
                output.fd.sync()
            }
            return "${file.name}: preserved complete records, repaired boundary=$needsSeparator, incomplete tail=$discarded bytes backed up"
        }
    }
}
