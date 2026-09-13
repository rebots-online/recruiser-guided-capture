package mba.robin.recruiser.capture

import java.io.File
import java.util.Base64
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CapturePackageWriterTest {
    @get:Rule val temporary = TemporaryFolder(File("build/test-work").apply { mkdirs() })
    private val timestamp = "9007199254740993"

    private fun manifest(): JSONObject = JSONObject().apply {
        put("format", "recruiser.capture"); put("version", 1); put("sessionId", "synthetic-native-fixture")
        put("createdAt", "2026-09-13T00:00:00Z"); put("state", "interrupted")
        put("provider", JSONObject().put("name", "arcore-android").put("version", "1.56.0")
            .put("device", "Synthetic JVM test fixture; not a phone recording").put("androidApi", 35))
        put("coordinateSystem", JSONObject().put("handedness", "right").put("up", "+Y").put("forward", "-Z").put("units", "meters"))
        put("capabilities", JSONObject().put("camera", true).put("pose", true).put("imu", true)
            .put("depth", true).put("confidence", true).put("location", false))
        put("clocks", JSONArray().put(JSONObject().put("id", "arcore-camera").put("unit", "nanoseconds")
            .put("offsetToSessionNs", "-$timestamp").put("uncertaintyNs", "0").put("source", "Synthetic known clock"))
            .put(JSONObject().put("id", "android-elapsed").put("unit", "nanoseconds")
                .put("offsetToSessionNs", JSONObject.NULL).put("uncertaintyNs", JSONObject.NULL).put("source", "Synthetic unaligned clock")))
        put("calibrations", JSONArray().put(JSONObject().put("id", "calibration-0").put("cameraId", "0")
            .put("width", 2).put("height", 2).put("fx", 2).put("fy", 2).put("cx", 0.5).put("cy", 0.5)
            .put("distortion", JSONObject.NULL).put("crop", JSONObject.NULL).put("rotationDegrees", 0).put("lensId", JSONObject.NULL)))
        put("segments", JSONArray().put(JSONObject().put("id", "segment-0").put("path", "originals/segment-0.mp4")
            .put("worldFrameId", "world-0").put("startTimestampNs", timestamp).put("endTimestampNs", timestamp)
            .put("state", "interrupted").put("mime", "video/mp4").put("width", 2).put("height", 2)))
        put("streams", JSONObject().apply {
            for (name in listOf("frames", "imu", "events", "quality")) put(name, "observations/$name.jsonl")
        })
        put("assets", JSONArray()); put("reconstructions", JSONArray())
    }

    private fun writer(manifest: JSONObject = manifest()): CapturePackageWriter {
        val directory = temporary.newFolder()
        File(directory, "manifest.json").writeText(manifest.toString())
        return CapturePackageWriter(directory, { 1000L }, { timestamp.toLong() })
    }

    @Test fun unstartedMissingMediaIsRemovedWithoutDiscardingExistingInterruptedMedia() {
        writer().use { output ->
            val unstarted = output.manifest.getJSONArray("segments").getJSONObject(0).put("startTimestampNs", "0")
            output.abandonUnstartedSegment(unstarted)
            assertEquals(0, output.manifest.getJSONArray("segments").length())
        }
        writer().use { output ->
            val unstarted = output.manifest.getJSONArray("segments").getJSONObject(0).put("startTimestampNs", "0")
            val bytes = byteArrayOf(1, 2, 3)
            output.writeAsset(unstarted.getString("path"), bytes)
            output.abandonUnstartedSegment(unstarted)
            assertEquals(1, output.manifest.getJSONArray("segments").length())
            assertEquals("interrupted", unstarted.getString("state"))
            assertArrayEquals(bytes, File(output.directory, unstarted.getString("path")).readBytes())
        }
        writer().use { output ->
            assertThrows(IllegalStateException::class.java) { output.abandonUnstartedSegment(output.manifest.getJSONArray("segments").getJSONObject(0)) }
        }
    }

    @Test fun nativeWriterExportsCrossLanguageFixtureWithOriginalBytesAndIntegerTimes() {
        val fixture = File("build/test-artifacts/native-roundtrip.recruiser-capture.zip").apply { parentFile!!.mkdirs() }
        writer().use { output ->
            // An interrupted, empty MP4 deliberately claims no playable recording; JPEG/depth are real byte assets.
            output.writeAsset("originals/segment-0.mp4", byteArrayOf())
            // A generated 2x2 checkerboard JPEG, embedded so Android unit-test compilation needs no java.desktop.
            val jpeg = Base64.getDecoder().decode(
                "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/" +
                "2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAACAAIDASIAAhEBAxEB/" +
                "8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/" +
                "8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDQkkdZGAdgASAAaKKK5Cj/2Q==")
            output.writeAsset("images/frame-0.jpg", jpeg)
            output.writeAsset("depth/frame-0.depth16", byteArrayOf(-24, 3, -24, 3, -24, 3, -24, 3))
            output.writeAsset("depth/frame-0.confidence8", byteArrayOf(-1, -1, -1, -1))
            output.append("frames", JSONObject().put("id", "frame-0").put("segmentId", "segment-0")
                .put("worldFrameId", "world-0").put("timestampNs", timestamp).put("clockId", "arcore-camera")
                .put("calibrationId", "calibration-0").put("tracking", "TRACKING").put("trackingFailure", JSONObject.NULL)
                .put("pose", JSONObject().put("position", JSONArray(listOf(0, 0, 0))).put("quaternion", JSONArray(listOf(0, 0, 0, 1))))
                .put("image", JSONObject().put("path", "images/frame-0.jpg").put("timestampNs", timestamp).put("width", 2).put("height", 2))
                .put("depth", JSONObject().put("path", "depth/frame-0.depth16").put("confidencePath", "depth/frame-0.confidence8")
                    .put("timestampNs", timestamp).put("width", 2).put("height", 2).put("fx", 2).put("fy", 2)
                    .put("cx", 0.5).put("cy", 0.5).put("stale", false)))
            for (sensor in listOf("accelerometer", "gyroscope")) output.append("imu", JSONObject().put("sensor", sensor)
                .put("timestampNs", timestamp).put("clockId", "android-elapsed").put("values", JSONArray(listOf(0, 0, 0))).put("accuracy", 3))
            output.event("interruption", "Synthetic test fixture: original MP4 unavailable for playback", timestamp.toLong(), "arcore-camera")
            output.append("quality", JSONObject().put("frameId", "frame-0").put("timestampNs", timestamp)
                .put("laplacianVariance", JSONObject.NULL).put("darkFraction", JSONObject.NULL).put("brightFraction", JSONObject.NULL)
                .put("overlapEstimate", JSONObject.NULL).put("findings", JSONArray().put(JSONObject().put("code", "image-unavailable")
                    .put("severity", "info").put("message", "Synthetic fixture has insufficient image dimensions for guidance"))))
            fixture.outputStream().use(output::exportArchive)
            ZipFile(fixture).use { zip ->
                val exported = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
                val assets = exported.getJSONArray("assets")
                assertEquals(8, assets.length())
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    val native = File(output.directory, asset.getString("path"))
                    assertEquals(CapturePackageWriter.sha256(native), asset.getString("sha256"))
                    assertArrayEquals(native.readBytes(), zip.getInputStream(zip.getEntry(asset.getString("path"))).readBytes())
                }
                val frame = JSONObject(zip.getInputStream(zip.getEntry("observations/frames.jsonl")).bufferedReader().readLine())
                assertEquals(timestamp, frame.getString("timestampNs"))
                assertEquals("-$timestamp", exported.getJSONArray("clocks").getJSONObject(0).getString("offsetToSessionNs"))
                assertArrayEquals(jpeg, zip.getInputStream(zip.getEntry("images/frame-0.jpg")).readBytes())
            }
        }
        println("Native writer synthetic fixture: ${fixture.absolutePath}")
    }
}
