package mba.robin.recruiser.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class ReconstructionTest {
    private val identity = CameraPose(0f, 0f, 0f, 0f, 0f, 0f, 1f)
    private val fingerprint = "a".repeat(64)

    private fun depth(
        values: IntArray = intArrayOf(1000), confidence: IntArray = IntArray(values.size) { 255 },
        width: Int = values.size, height: Int = 1,
        intrinsics: CameraIntrinsics = CameraIntrinsics(width, height, 10f, 10f, 0f, 0f),
    ) = DepthImage(width, height, ShortArray(values.size) { values[it].toShort() },
        ByteArray(confidence.size) { confidence[it].toByte() }, intrinsics)

    private fun observation(
        timestamp: Long = 1, image: DepthImage = depth(), pose: CameraPose = identity,
        color: RgbImage? = null, world: String = "world-1", id: String = "frame-$timestamp",
    ) = DepthObservation(id, world, timestamp, pose, image, color)

    private fun ply(fusion: VoxelFusion): String = ByteArrayOutputStream().also(fusion::writePly).toString("UTF-8")
    private fun vertices(fusion: VoxelFusion): List<List<Double>> = ply(fusion).substringAfter("end_header\n")
        .lineSequence().filter(String::isNotBlank).map { row -> row.split(' ').map(String::toDouble) }.toList()
    private fun checkpoint(fusion: VoxelFusion, next: Int): ByteArray = ByteArrayOutputStream().also {
        fusion.saveCheckpoint(it, next, fingerprint)
    }.toByteArray()

    @Test fun calibratedPlaneHasKnownMetricCoordinatesAndCameraAxisSigns() {
        val intrinsics = CameraIntrinsics(3, 3, 2f, 4f, 1f, 1f)
        val points = CpuDepthUnprojector().unproject(depth(IntArray(9) { 2000 }, width = 3, height = 3, intrinsics = intrinsics), 128)
        assertArrayEquals(floatArrayOf(-1f, 0.5f, -2f), points.copyOfRange(0, 3), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, -2f), points.copyOfRange(12, 15), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, -0.5f, -2f), points.copyOfRange(24, 27), 1e-6f)
    }

    @Test fun unsignedConfidenceAndDepthBoundariesAreNotConfusedWithSignedStorage() {
        val image = depth(intArrayOf(100, 10000, 99, 10001, 65535, 1000, 1000),
            intArrayOf(128, 255, 255, 255, 255, 127, 128))
        val points = CpuDepthUnprojector().unproject(image, 128)
        assertEquals(-0.1f, points[2], 1e-6f)
        assertEquals(-10f, points[5], 1e-6f)
        for (index in 2..5) assertTrue(points.copyOfRange(index * 3, index * 3 + 3).all(Float::isNaN))
        assertEquals(-1f, points[20], 1e-6f)
        assertThrows(IllegalArgumentException::class.java) { CpuDepthUnprojector().unproject(image, 256) }
    }

    @Test fun cameraImagesOwnArraysAndRejectInvalidDimensionsOrCalibration() {
        val values = shortArrayOf(1000)
        val confidence = byteArrayOf(-1)
        val calibration = CameraIntrinsics(1, 1, 1f, 1f, 0f, 0f)
        val image = DepthImage(1, 1, values, confidence, calibration)
        values[0] = 0; confidence[0] = 0
        image.millimeters[0] = 0; image.confidence[0] = 0
        assertEquals(-1f, CpuDepthUnprojector().unproject(image, 128)[2], 1e-6f)
        val colorBytes = byteArrayOf(1, 2, 3)
        val color = RgbImage(1, 1, colorBytes, calibration)
        colorBytes[0] = 0; color.rgb[1] = 0
        assertArrayEquals(byteArrayOf(1, 2, 3), color.rgb)
        assertThrows(IllegalArgumentException::class.java) { CameraIntrinsics(Int.MAX_VALUE, 2, 1f, 1f, 0f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { CameraIntrinsics(1, 1, 0f, 1f, 0f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { CameraIntrinsics(1, 1, 1f, 1f, Float.NaN, 0f) }
        assertThrows(IllegalArgumentException::class.java) { DepthImage(2, 1, values, confidence, calibration) }
        assertThrows(IllegalArgumentException::class.java) { RgbImage(1, 1, byteArrayOf(1), calibration) }
        assertThrows(IllegalArgumentException::class.java) { identity.copy(qw = 0f) }
        assertThrows(IllegalArgumentException::class.java) { identity.copy(tx = Float.POSITIVE_INFINITY) }
    }

    @Test fun rotatedTranslatedPoseUsesCameraToWorldQuaternion() {
        val half = sqrt(0.5).toFloat()
        val pose = CameraPose(1f, 2f, 3f, 0f, half, 0f, half)
        val fusion = VoxelFusion()
        fusion.integrate(observation(pose = pose))
        val point = vertices(fusion).single()
        assertEquals(0.0, point[0], 1e-6)
        assertEquals(2.0, point[1], 1e-6)
        assertEquals(3.0, point[2], 1e-6)
    }

    @Test fun colourUsesItsOwnCalibrationAndOutOfBoundsRemainsUnobserved() {
        val color = RgbImage(3, 1, byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90), CameraIntrinsics(3, 1, 20f, 10f, 0f, 0f))
        val fusion = VoxelFusion()
        fusion.integrate(observation(image = depth(intArrayOf(1000, 1000, 1000)), color = color))
        val points = vertices(fusion)
        assertEquals(listOf(10.0, 20.0, 30.0), points[0].takeLast(3))
        assertEquals(listOf(70.0, 80.0, 90.0), points[1].takeLast(3))
        assertEquals(listOf(160.0, 160.0, 160.0), points[2].takeLast(3))
        val restored = VoxelFusion.restoreCheckpoint(ByteArrayInputStream(checkpoint(fusion, 1)), fingerprint).fusion
        assertEquals(ply(fusion), ply(restored))
    }

    @Test fun confidenceWeightedColoursRetainRealBlackPixels() {
        fun blackOrWhite(value: Int) = RgbImage(1, 1, ByteArray(3) { value.toByte() }, CameraIntrinsics(1, 1, 1f, 1f, 0f, 0f))
        val fusion = VoxelFusion()
        fusion.integrate(observation(color = blackOrWhite(0)))
        fusion.integrate(observation(2, image = depth(confidence = intArrayOf(128)), color = blackOrWhite(255)))
        assertEquals(listOf(85.0, 85.0, 85.0), vertices(fusion).single().takeLast(3))
    }

    @Test fun capacityBoundsGrowthButExistingVoxelsContinueImproving() {
        val fusion = VoxelFusion(maxVoxels = 1)
        val first = fusion.integrate(observation(image = depth(intArrayOf(1000, 1000))))
        assertEquals(FusionProgress(1, 1, true), first)
        val second = fusion.integrate(observation(2, pose = identity.copy(tx = 0.01f)))
        assertEquals(FusionProgress(1, 1, true), second)
        assertEquals(0.005, vertices(fusion).single()[0], 1e-7)
        assertThrows(IllegalArgumentException::class.java) { VoxelFusion(maxVoxels = 0) }
        assertThrows(IllegalArgumentException::class.java) { VoxelFusion(maxVoxels = Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { VoxelFusion(voxelSizeMeters = Float.NaN) }
    }

    @Test fun unrelatedWorldsAndDuplicateOrOutOfOrderFramesCannotInflateSupport() {
        val fusion = VoxelFusion()
        fusion.integrate(observation(5))
        val before = ply(fusion)
        assertThrows(IllegalArgumentException::class.java) { fusion.integrate(observation(6, world = "reset-world")) }
        assertThrows(IllegalArgumentException::class.java) { fusion.integrate(observation(5)) }
        assertThrows(IllegalArgumentException::class.java) { fusion.integrate(observation(4)) }
        assertThrows(IllegalArgumentException::class.java) { fusion.integrate(observation(6, id = "frame-5")) }
        assertEquals(before, ply(fusion))
    }

    @Test fun invalidDepthAndInvalidInjectedOutputCannotBecomePoints() {
        val empty = VoxelFusion()
        assertEquals(0, empty.integrate(observation(image = depth(intArrayOf(0, 65535)))).acceptedSamples)
        assertTrue(vertices(empty).isEmpty())
        val nan = VoxelFusion(unprojector = object : DepthUnprojector {
            override fun unproject(depth: DepthImage, minConfidence: Int) = floatArrayOf(Float.NaN, 0f, -1f)
        })
        assertEquals(0, nan.integrate(observation()).acceptedSamples)
        val wrongShape = VoxelFusion(unprojector = object : DepthUnprojector {
            override fun unproject(depth: DepthImage, minConfidence: Int) = FloatArray(0)
        })
        assertThrows(IllegalArgumentException::class.java) { wrongShape.integrate(observation()) }
    }

    @Test fun interruptedResumeMatchesUninterruptedFusionAndRejectsReplay() {
        val inputs = (1L..8L).map { observation(it, pose = identity.copy(tx = it * 0.004f)) }
        val uninterrupted = VoxelFusion()
        inputs.forEach(uninterrupted::integrate)
        val interrupted = VoxelFusion()
        inputs.take(3).forEach(interrupted::integrate)
        val saved = checkpoint(interrupted, 3)
        val restored = VoxelFusion.restoreCheckpoint(ByteArrayInputStream(saved), fingerprint)
        assertEquals(3, restored.nextFrameIndex)
        assertThrows(IllegalArgumentException::class.java) { restored.fusion.integrate(inputs[2]) }
        inputs.drop(restored.nextFrameIndex).forEach(restored.fusion::integrate)
        assertEquals(ply(uninterrupted), ply(restored.fusion))
        assertArrayEquals(checkpoint(uninterrupted, 8), checkpoint(restored.fusion, 8))
    }

    @Test fun fingerprintTruncationChecksumAndTrailingDataAreValidated() {
        val fusion = VoxelFusion().also { it.integrate(observation()) }
        val bytes = checkpoint(fusion, 1)
        assertThrows(IllegalArgumentException::class.java) { VoxelFusion.restoreCheckpoint(ByteArrayInputStream(bytes), "b".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { fusion.saveCheckpoint(ByteArrayOutputStream(), 0, fingerprint) }
        assertThrows(IllegalArgumentException::class.java) { fusion.saveCheckpoint(ByteArrayOutputStream(), 1, "b".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { fusion.saveCheckpoint(ByteArrayOutputStream(), 1, "invalid") }
        for (size in listOf(0, 8, 13, bytes.size - 1)) {
            assertThrows(Exception::class.java) { VoxelFusion.restoreCheckpoint(ByteArrayInputStream(bytes.copyOf(size)), fingerprint) }
        }
        val corrupted = bytes.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { VoxelFusion.restoreCheckpoint(ByteArrayInputStream(corrupted), fingerprint) }
        assertThrows(IllegalArgumentException::class.java) { VoxelFusion.restoreCheckpoint(ByteArrayInputStream(bytes + byteArrayOf(0)), fingerprint) }
        val enormous = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(0x52434631); writeInt(1); writeInt(Int.MAX_VALUE) }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { VoxelFusion.restoreCheckpoint(ByteArrayInputStream(enormous), fingerprint) }
    }

    @Test fun validHashDoesNotMakeMalformedVoxelStateAcceptable() {
        val bytes = checkpoint(VoxelFusion().also { it.integrate(observation()) }, 1)
        val payloadLength = ByteBuffer.wrap(bytes, 8, 4).int
        val payload = bytes.copyOfRange(12, 12 + payloadLength)
        // The final cell is 92 bytes: key (12), XYZ sums (24), then weight.
        ByteBuffer.wrap(payload).putDouble(payload.size - 92 + 12, Double.NaN)
        val forged = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).apply {
                writeInt(0x52434631); writeInt(1); writeInt(payload.size); write(payload)
                write(MessageDigest.getInstance("SHA-256").digest(payload))
            }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { VoxelFusion.restoreCheckpoint(ByteArrayInputStream(forged), fingerprint) }
    }

    @Test fun emptyMapCanBeCheckpointedAndPlyDoesNotDependOnDeviceLocale() {
        val empty = VoxelFusion.restoreCheckpoint(ByteArrayInputStream(checkpoint(VoxelFusion(), 4)), fingerprint)
        assertEquals(4, empty.nextFrameIndex)
        assertTrue(vertices(empty.fusion).isEmpty())
        val fusion = VoxelFusion().also { it.integrate(observation()) }
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals(-1.0, vertices(fusion).single()[2], 0.0)
        } finally { Locale.setDefault(original) }
    }
}
