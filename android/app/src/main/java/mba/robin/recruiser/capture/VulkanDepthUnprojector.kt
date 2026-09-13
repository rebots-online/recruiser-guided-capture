package mba.robin.recruiser.capture

import mba.robin.recruiser.core.CpuDepthUnprojector
import mba.robin.recruiser.core.DepthImage
import mba.robin.recruiser.core.DepthUnprojector

/** Vulkan accelerates projection; the caller still owns fusion and frame registration. */
class VulkanDepthUnprojector : DepthUnprojector {
    private val reference = CpuDepthUnprojector()
    private var useVulkan = loadLibrary()
    var backend: String = "CPU reference (Vulkan not yet exercised)"
        private set
    var fallbackReason: String? = if (useVulkan) null else "Native Vulkan library unavailable"
        private set

    @Synchronized
    override fun unproject(depth: DepthImage, minConfidence: Int): FloatArray {
        require(minConfidence in 0..255)
        if (useVulkan) {
            try {
                val i = depth.intrinsics
                val result = unprojectNative(depth.millimeters, depth.confidence,
                    depth.width, depth.height, i.fx, i.fy, i.cx, i.cy, minConfidence)
                require(result.size.toLong() == depth.width.toLong() * depth.height * 3)
                backend = "Vulkan unprojection + CPU voxel fusion"
                return result
            } catch (e: RuntimeException) {
                fallbackReason = e.message ?: e.javaClass.simpleName
                useVulkan = false
            } catch (e: LinkageError) {
                fallbackReason = e.message ?: "Native Vulkan entry point unavailable"
                useVulkan = false
            }
        }
        backend = "CPU reference"
        return reference.unproject(depth, minConfidence)
    }

    private external fun unprojectNative(depth: ShortArray, confidence: ByteArray,
        width: Int, height: Int, fx: Float, fy: Float, cx: Float, cy: Float,
        minConfidence: Int): FloatArray

    companion object {
        private fun loadLibrary(): Boolean = try {
            System.loadLibrary("recruiser_depth"); true
        } catch (_: LinkageError) { false }
    }
}
