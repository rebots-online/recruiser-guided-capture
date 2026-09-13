package mba.robin.recruiser.core

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class QualityAnalyzerTest {
    private val identity = CameraPose(0f, 0f, 0f, 0f, 0f, 0f, 1f)
    private val width = 80
    private val height = 60
    private fun detailedImage() = ByteArray(width * height) { if ((it % width + it / width) % 2 == 0) 60 else 180.toByte() }
    private fun analyze(
        analyzer: QualityAnalyzer = QualityAnalyzer(), image: ByteArray = detailedImage(),
        tracking: TrackingQuality = TrackingQuality.TRACKING, pose: CameraPose? = identity, timestamp: Long = 1,
    ) = analyzer.analyze("frame-$timestamp", timestamp, image, width, height, tracking, pose)
    private fun QualityReport.has(code: String) = findings.any { it.code == code }

    @Test fun sharpDetailedImageHasKnownLaplacianVarianceAndNoFalseQualityPassOnOverlap() {
        val report = analyze()
        assertEquals(230400.0, report.laplacianVariance!!, 1e-6)
        assertEquals(0.0, report.darkFraction!!, 0.0)
        assertEquals(0.0, report.brightFraction!!, 0.0)
        assertFalse(report.has("image-soft"))
        assertFalse(report.has("low-texture"))
        assertNull(report.overlapEstimate)
        assertTrue(report.has("overlap-unavailable"))
    }

    @Test fun uniformImageSignalsUnknownSharpnessInsteadOfCallingItSharp() {
        val report = analyze(image = ByteArray(width * height) { 120 })
        assertTrue(report.has("low-texture"))
        assertFalse(report.has("image-soft"))
        assertNull(report.overlapEstimate)
    }

    @Test fun softGradientWarnsBlurWhenTextureEvidenceIsSufficient() {
        val report = analyze(image = ByteArray(width * height) { (40 + 2 * (it % width)).toByte() })
        assertEquals(0.0, report.laplacianVariance!!, 0.0)
        assertTrue(report.has("image-soft"))
        assertFalse(report.has("low-texture"))
        assertNull(report.overlapEstimate)
    }

    @Test fun clippingChecksTreatLumaAsUnsignedAndRespectThresholds() {
        val dark = analyze(image = ByteArray(width * height) { 12 })
        assertEquals(1.0, dark.darkFraction!!, 0.0)
        assertTrue(dark.has("underexposed"))
        val bright = analyze(image = ByteArray(width * height) { 243.toByte() })
        assertEquals(1.0, bright.brightFraction!!, 0.0)
        assertTrue(bright.has("overexposed"))
        assertFalse(bright.has("underexposed"))
        val below = analyze(image = ByteArray(width * height) { if (it < 1680) 12 else 120 })
        assertEquals(0.35, below.darkFraction!!, 0.0)
        assertFalse(below.has("underexposed"))
    }

    @Test fun noImageOrInvalidShapeCannotProduceSuccessfulMetrics() {
        val analyzer = QualityAnalyzer()
        analyze(analyzer)
        val report = analyzer.analyze("missing", 2, ByteArray(0), width, height, TrackingQuality.TRACKING, identity)
        assertTrue(report.has("image-unavailable"))
        assertNull(report.laplacianVariance)
        assertNull(report.darkFraction)
        assertNull(report.brightFraction)
        assertNull(report.overlapEstimate)
        assertNull(analyze(analyzer, timestamp = 3).overlapEstimate)
        assertThrows(IllegalArgumentException::class.java) {
            analyzer.analyze("missing", -1, ByteArray(0), 0, 0, TrackingQuality.STOPPED, null)
        }
    }

    @Test fun overlapMeasuresPoseChangeAndNeverClaimsSurfaceCompleteness() {
        val analyzer = QualityAnalyzer()
        analyze(analyzer)
        val nearby = analyze(analyzer, pose = identity.copy(tx = 0.1f), timestamp = 2)
        assertEquals(0.8, nearby.overlapEstimate!!, 1e-6)
        val far = analyze(analyzer, pose = identity.copy(tx = 1f), timestamp = 3)
        assertEquals(0.0, far.overlapEstimate!!, 0.0)
        assertTrue(far.has("low-overlap"))
        assertTrue(far.findings.single { it.code == "low-overlap" }.message.contains("not a coverage map"))
    }

    @Test fun quaternionSignDoesNotChangeOverlapButRotationDoes() {
        val analyzer = QualityAnalyzer()
        analyze(analyzer)
        val same = analyze(analyzer, pose = identity.copy(qw = -1f), timestamp = 2)
        assertEquals(1.0, same.overlapEstimate!!, 1e-6)
        val rotation = identity.copy(qy = sin(0.35).toFloat(), qw = cos(0.35).toFloat())
        val rotated = analyze(analyzer, pose = rotation, timestamp = 3)
        assertEquals(0.0, rotated.overlapEstimate!!, 1e-6)
        assertTrue(rotated.has("low-overlap"))
    }

    @Test fun trackingLossClearsReferenceAndNoPoseIsNeverAnIdentityPose() {
        val analyzer = QualityAnalyzer()
        analyze(analyzer)
        val lost = analyze(analyzer, tracking = TrackingQuality.PAUSED, timestamp = 2)
        assertTrue(lost.has("tracking-lost"))
        assertNull(lost.overlapEstimate)
        val restored = analyze(analyzer, timestamp = 3)
        assertNull(restored.overlapEstimate)
        assertFalse(restored.has("tracking-lost"))
        assertNotNull(analyze(analyzer, timestamp = 4).overlapEstimate)
        assertNull(analyze(analyzer, pose = null, timestamp = 5).overlapEstimate)
        assertNull(analyze(analyzer, timestamp = 6).overlapEstimate)
        assertTrue(analyze(analyzer, tracking = TrackingQuality.STOPPED, timestamp = 7).has("tracking-lost"))
    }

    @Test fun poorImageDoesNotReplaceTheLastUsefulPoseReference() {
        val analyzer = QualityAnalyzer()
        analyze(analyzer)
        val weak = analyze(analyzer, image = ByteArray(width * height) { 120 }, pose = identity.copy(tx = 2f), timestamp = 2)
        assertNull(weak.overlapEstimate)
        assertEquals(1.0, analyze(analyzer, timestamp = 3).overlapEstimate!!, 1e-6)
    }

    @Test fun largeImagesUseBoundedSamplingAndKeepFrameSourceTimestamp() {
        val image = ByteArray(1920 * 1080) { index ->
            if (((index % 1920) / 12 + (index / 1920) / 9) % 2 == 0) 60 else 180.toByte()
        }
        val timestamp = 9_007_199_254_740_993L
        val report = QualityAnalyzer().analyze("source-frame", timestamp, image, 1920, 1080, TrackingQuality.TRACKING, identity)
        assertEquals(timestamp, report.timestampNs)
        assertEquals("source-frame", report.frameId)
        assertTrue(report.laplacianVariance!! >= 60)
        assertFalse(report.has("image-soft"))
    }
}
