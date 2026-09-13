package mba.robin.recruiser.core

import kotlin.math.abs
import kotlin.math.sqrt

internal const val MAX_IMAGE_PIXELS = 4_000_000

internal fun imagePixels(width: Int, height: Int): Int {
    require(width > 0 && height > 0 && width.toLong() * height <= MAX_IMAGE_PIXELS) {
        "Image dimensions must contain between 1 and $MAX_IMAGE_PIXELS pixels"
    }
    return width * height
}

data class CameraIntrinsics(
    val width: Int, val height: Int,
    val fx: Float, val fy: Float, val cx: Float, val cy: Float,
) {
    init {
        imagePixels(width, height)
        require(fx.isFinite() && fy.isFinite() && fx > 0 && fy > 0) { "Invalid focal length" }
        require(cx.isFinite() && cy.isFinite()) { "Invalid principal point" }
    }
}

data class CameraPose(
    val tx: Float, val ty: Float, val tz: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
) {
    init {
        require(listOf(tx, ty, tz, qx, qy, qz, qw).all { it.isFinite() }) { "Non-finite camera pose" }
        val normSquared = qx.toDouble() * qx + qy.toDouble() * qy + qz.toDouble() * qz + qw.toDouble() * qw
        require(abs(normSquared - 1.0) <= 0.001) { "Camera quaternion must be normalized" }
    }

    internal fun normalizedQuaternion(): DoubleArray {
        val norm = sqrt(qx.toDouble() * qx + qy.toDouble() * qy + qz.toDouble() * qz + qw.toDouble() * qw)
        return doubleArrayOf(qx / norm, qy / norm, qz / norm, qw / norm)
    }
}

/** Arrays are owned by this value; public access never exposes the retained bytes. */
class DepthImage(
    val width: Int, val height: Int,
    millimeters: ShortArray, confidence: ByteArray, val intrinsics: CameraIntrinsics,
) {
    private val retainedDepth: ShortArray
    private val retainedConfidence: ByteArray
    init {
        val pixels = imagePixels(width, height)
        require(intrinsics.width == width && intrinsics.height == height) { "Depth calibration shape mismatch" }
        require(millimeters.size == pixels && confidence.size == pixels) { "Depth plane shape mismatch" }
        retainedDepth = millimeters.copyOf()
        retainedConfidence = confidence.copyOf()
    }
    val millimeters: ShortArray get() = retainedDepth.copyOf()
    val confidence: ByteArray get() = retainedConfidence.copyOf()
    internal fun depthAt(index: Int): Int = retainedDepth[index].toInt() and 0xffff
    internal fun confidenceAt(index: Int): Int = retainedConfidence[index].toInt() and 0xff
}

class RgbImage(
    val width: Int, val height: Int, rgb: ByteArray, val intrinsics: CameraIntrinsics,
) {
    private val retainedRgb: ByteArray
    init {
        val pixels = imagePixels(width, height)
        require(intrinsics.width == width && intrinsics.height == height) { "RGB calibration shape mismatch" }
        require(rgb.size == pixels * 3) { "RGB plane shape mismatch" }
        retainedRgb = rgb.copyOf()
    }
    val rgb: ByteArray get() = retainedRgb.copyOf()
    internal fun channelAt(index: Int): Int = retainedRgb[index].toInt() and 0xff
}

data class DepthObservation(
    val frameId: String, val worldFrameId: String, val timestampNs: Long,
    val pose: CameraPose, val depth: DepthImage, val color: RgbImage?,
) {
    init {
        require(frameId.isNotBlank() && frameId.length <= 256) { "Invalid frame ID" }
        require(worldFrameId.isNotBlank() && worldFrameId.length <= 256) { "Invalid world frame ID" }
        require(timestampNs >= 0) { "Negative observation timestamp" }
    }
}

interface DepthUnprojector {
    /** Packed XYZ in metres: +X right, +Y up, -Z forward; invalid triples are NaN. */
    fun unproject(depth: DepthImage, minConfidence: Int): FloatArray
}

class CpuDepthUnprojector : DepthUnprojector {
    override fun unproject(depth: DepthImage, minConfidence: Int): FloatArray {
        require(minConfidence in 0..255) { "Confidence threshold must be unsigned 8-bit" }
        val points = FloatArray(depth.width * depth.height * 3) { Float.NaN }
        val intrinsics = depth.intrinsics
        for (index in 0 until depth.width * depth.height) {
            val millimeters = depth.depthAt(index)
            if (millimeters !in 100..10000 || depth.confidenceAt(index) < minConfidence) continue
            val distance = millimeters * 0.001f
            val x = (index % depth.width - intrinsics.cx) * distance / intrinsics.fx
            val y = -(index / depth.width - intrinsics.cy) * distance / intrinsics.fy
            if (!x.isFinite() || !y.isFinite()) continue
            points[index * 3] = x
            points[index * 3 + 1] = y
            points[index * 3 + 2] = -distance
        }
        return points
    }
}

data class FusionProgress(val acceptedSamples: Int, val voxelCount: Int, val capacityReached: Boolean)
data class FusionCheckpoint(val fusion: VoxelFusion, val nextFrameIndex: Int)
enum class TrackingQuality { TRACKING, PAUSED, STOPPED }
data class QualityFinding(val code: String, val severity: String, val message: String)
data class QualityReport(
    val frameId: String, val timestampNs: Long,
    val laplacianVariance: Double?, val darkFraction: Double?, val brightFraction: Double?,
    val overlapEstimate: Double?, val findings: List<QualityFinding>,
)
