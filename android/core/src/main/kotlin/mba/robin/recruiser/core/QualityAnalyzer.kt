package mba.robin.recruiser.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** Bounded provisional checks; none of these scores certifies mapped surface coverage. */
class QualityAnalyzer {
    private var previousUsefulPose: CameraPose? = null

    fun analyze(
        frameId: String, timestampNs: Long, luma: ByteArray, width: Int, height: Int,
        tracking: TrackingQuality, pose: CameraPose?,
    ): QualityReport {
        require(frameId.isNotBlank() && timestampNs >= 0) { "Quality report requires frame identity and source time" }
        val findings = mutableListOf<QualityFinding>()
        if (tracking != TrackingQuality.TRACKING) {
            previousUsefulPose = null
            findings.add(QualityFinding("tracking-lost", "warning", "Camera tracking unavailable; pause the sweep until tracking returns."))
        }
        if (pose == null) previousUsefulPose = null
        if (width < 3 || height < 3 || width.toLong() * height != luma.size.toLong()) {
            previousUsefulPose = null
            findings.add(QualityFinding("image-unavailable", "warning", "Image quality cannot be checked for this frame."))
            findings.add(QualityFinding("overlap-unavailable", "info", "Overlap cannot be estimated without a usable image."))
            return QualityReport(frameId, timestampNs, null, null, null, null, findings.toList())
        }

        val strideX = ceil(width / 160.0).toInt().coerceAtLeast(1)
        val strideY = ceil(height / 120.0).toInt().coerceAtLeast(1)
        val sampleWidth = (width - 1) / strideX + 1
        val sampleHeight = (height - 1) / strideY + 1
        val samples = IntArray(sampleWidth * sampleHeight)
        var sum = 0.0
        var squaredSum = 0.0
        var dark = 0
        var bright = 0
        for (y in 0 until sampleHeight) for (x in 0 until sampleWidth) {
            val value = luma[y * strideY * width + x * strideX].toInt() and 0xff
            samples[y * sampleWidth + x] = value
            sum += value
            squaredSum += value.toDouble() * value
            if (value <= 12) dark++
            if (value >= 243) bright++
        }
        val standardDeviation = sqrt(max(0.0, squaredSum / samples.size - (sum / samples.size) * (sum / samples.size)))
        var lapSum = 0.0
        var lapSquaredSum = 0.0
        var lapCount = 0
        for (y in 1 until sampleHeight - 1) for (x in 1 until sampleWidth - 1) {
            val index = y * sampleWidth + x
            val laplacian = samples[index - 1] + samples[index + 1] + samples[index - sampleWidth] +
                samples[index + sampleWidth] - 4 * samples[index]
            lapSum += laplacian
            lapSquaredSum += laplacian.toDouble() * laplacian
            lapCount++
        }
        val laplacianVariance = if (lapCount == 0) null else
            max(0.0, lapSquaredSum / lapCount - (lapSum / lapCount) * (lapSum / lapCount))
        val darkFraction = dark.toDouble() / samples.size
        val brightFraction = bright.toDouble() / samples.size
        val lowTexture = standardDeviation < 8
        if (lowTexture) {
            findings.add(QualityFinding("low-texture", "info", "Too little image texture to judge sharpness reliably; include visible detail."))
        } else if (laplacianVariance == null) {
            findings.add(QualityFinding("image-unavailable", "warning", "Insufficient image area to assess sharpness."))
        } else if (laplacianVariance < 60) {
            findings.add(QualityFinding("image-soft", "warning", "Image appears soft; try holding steadier or moving more slowly."))
        }
        if (darkFraction > 0.35) {
            findings.add(QualityFinding("underexposed", "warning", "Much of this image is very dark; try improving the lighting."))
        }
        if (brightFraction > 0.35) {
            findings.add(QualityFinding("overexposed", "warning", "Much of this image is clipped bright; avoid glare or reduce exposure."))
        }

        var overlap: Double? = null
        val usefulImage = !lowTexture && laplacianVariance != null && laplacianVariance >= 60 &&
            darkFraction <= 0.35 && brightFraction <= 0.35
        val previous = previousUsefulPose
        if (tracking == TrackingQuality.TRACKING && pose != null && usefulImage) {
            if (previous != null) {
                val q1 = pose.normalizedQuaternion()
                val q2 = previous.normalizedQuaternion()
                val dot = abs(q1.indices.sumOf { q1[it] * q2[it] }).coerceIn(0.0, 1.0)
                val angle = 2 * acos(dot)
                val dx = pose.tx.toDouble() - previous.tx
                val dy = pose.ty.toDouble() - previous.ty
                val dz = pose.tz.toDouble() - previous.tz
                val distance = sqrt(dx * dx + dy * dy + dz * dz)
                overlap = (1 - angle / 0.7 - distance / 0.5).coerceIn(0.0, 1.0)
                if (overlap < 0.35) {
                    findings.add(QualityFinding("low-overlap", "warning", "Large viewpoint change; include views closer to the previous useful view. This is a pose heuristic, not a coverage map."))
                }
            }
            previousUsefulPose = pose
        }
        if (overlap == null) {
            findings.add(QualityFinding("overlap-unavailable", "info", "Overlap needs recent useful images and valid camera tracking; mapped completeness is unknown."))
        }
        return QualityReport(frameId, timestampNs, laplacianVariance, darkFraction, brightFraction, overlap, findings.toList())
    }
}
