package mba.robin.recruiser.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Confidence-weighted coloured point fusion, with bounded geometry and no inferred surfaces. */
class VoxelFusion(
    val voxelSizeMeters: Float = 0.03f,
    val maxVoxels: Int = 250_000,
    private val unprojector: DepthUnprojector = CpuDepthUnprojector(),
) {
    private data class Key(val x: Int, val y: Int, val z: Int)
    private data class Cell(
        var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0, var weight: Double = 0.0,
        var red: Double = 0.0, var green: Double = 0.0, var blue: Double = 0.0,
        var colorWeight: Double = 0.0, var samples: Long = 0, var colorSamples: Long = 0,
    )
    private val cells = LinkedHashMap<Key, Cell>()
    private var worldFrameId: String? = null
    private var lastTimestampNs = -1L
    private var lastFrameId = ""
    private var integratedFrames = 0
    private var fingerprintBinding: String? = null
    private var reachedCapacity = false

    init {
        require(voxelSizeMeters.isFinite() && voxelSizeMeters in 0.0001f..10f) { "Voxel size outside supported bounds" }
        require(maxVoxels in 1..MAX_VOXELS) { "Voxel capacity outside supported bounds" }
    }

    /** Calls must be serialized by the reconstruction job; each source frame is consumed once. */
    fun integrate(observation: DepthObservation): FusionProgress {
        require(worldFrameId == null || worldFrameId == observation.worldFrameId) { "Cannot fuse distinct world frames" }
        require(observation.timestampNs > lastTimestampNs && observation.frameId != lastFrameId) {
            "Duplicate or out-of-order observation"
        }
        check(integratedFrames < Int.MAX_VALUE) { "Reconstruction frame count exhausted" }
        val points = unprojector.unproject(observation.depth, MIN_CONFIDENCE)
        require(points.size == observation.depth.width * observation.depth.height * 3) { "Unprojection output shape mismatch" }
        val q = observation.pose.normalizedQuaternion()
        var accepted = 0
        for (index in 0 until points.size / 3) {
            if (observation.depth.depthAt(index) !in 100..10000 || observation.depth.confidenceAt(index) < MIN_CONFIDENCE) continue
            val x = points[index * 3].toDouble()
            val y = points[index * 3 + 1].toDouble()
            val z = points[index * 3 + 2].toDouble()
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || z >= 0) continue
            val crossX = 2 * (q[1] * z - q[2] * y)
            val crossY = 2 * (q[2] * x - q[0] * z)
            val crossZ = 2 * (q[0] * y - q[1] * x)
            val worldX = x + q[3] * crossX + q[1] * crossZ - q[2] * crossY + observation.pose.tx
            val worldY = y + q[3] * crossY + q[2] * crossX - q[0] * crossZ + observation.pose.ty
            val worldZ = z + q[3] * crossZ + q[0] * crossY - q[1] * crossX + observation.pose.tz
            val key = keyFor(worldX, worldY, worldZ) ?: continue
            var cell = cells[key]
            if (cell == null) {
                if (cells.size >= maxVoxels) {
                    reachedCapacity = true
                    continue
                }
                cell = Cell()
                cells[key] = cell
            }
            check(cell.samples < Long.MAX_VALUE) { "Voxel observation counter exhausted" }
            val weight = observation.depth.confidenceAt(index) / 255.0
            cell.x += worldX * weight
            cell.y += worldY * weight
            cell.z += worldZ * weight
            cell.weight += weight
            cell.samples++
            val color = observation.color
            if (color != null) {
                val pixelX = color.intrinsics.fx * x / -z + color.intrinsics.cx
                val pixelY = -color.intrinsics.fy * y / -z + color.intrinsics.cy
                if (pixelX.isFinite() && pixelY.isFinite() && pixelX >= -0.5 && pixelY >= -0.5 &&
                    pixelX < color.width - 0.5 && pixelY < color.height - 0.5
                ) {
                    val colorIndex = (floor(pixelY + 0.5).toInt() * color.width + floor(pixelX + 0.5).toInt()) * 3
                    cell.red += color.channelAt(colorIndex) * weight
                    cell.green += color.channelAt(colorIndex + 1) * weight
                    cell.blue += color.channelAt(colorIndex + 2) * weight
                    cell.colorWeight += weight
                    cell.colorSamples++
                }
            }
            accepted++
        }
        worldFrameId = observation.worldFrameId
        lastTimestampNs = observation.timestampNs
        lastFrameId = observation.frameId
        integratedFrames++
        return FusionProgress(accepted, cells.size, reachedCapacity || cells.size == maxVoxels)
    }

    private fun keyFor(x: Double, y: Double, z: Double): Key? {
        val kx = floor(x / voxelSizeMeters)
        val ky = floor(y / voxelSizeMeters)
        val kz = floor(z / voxelSizeMeters)
        if (!kx.isFinite() || !ky.isFinite() || !kz.isFinite() ||
            kx < Int.MIN_VALUE || kx > Int.MAX_VALUE || ky < Int.MIN_VALUE || ky > Int.MAX_VALUE ||
            kz < Int.MIN_VALUE || kz > Int.MAX_VALUE
        ) return null
        return Key(kx.toInt(), ky.toInt(), kz.toInt())
    }

    fun writePly(output: OutputStream) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8).buffered()
        writer.write("ply\nformat ascii 1.0\ncomment Recruiser confidence-weighted observed points; unobserved colour is neutral grey\n")
        writer.write("element vertex ${cells.size}\nproperty float x\nproperty float y\nproperty float z\n")
        writer.write("property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n")
        for (cell in cells.values) {
            val red = if (cell.colorWeight == 0.0) 160 else (cell.red / cell.colorWeight).roundToInt().coerceIn(0, 255)
            val green = if (cell.colorWeight == 0.0) 160 else (cell.green / cell.colorWeight).roundToInt().coerceIn(0, 255)
            val blue = if (cell.colorWeight == 0.0) 160 else (cell.blue / cell.colorWeight).roundToInt().coerceIn(0, 255)
            writer.write(String.format(Locale.ROOT, "%.9g %.9g %.9g %d %d %d\n",
                cell.x / cell.weight, cell.y / cell.weight, cell.z / cell.weight, red, green, blue))
        }
        writer.flush()
    }

    /** The caller atomically replaces the durable file after flushing and fsyncing this stream. */
    fun saveCheckpoint(output: OutputStream, nextFrameIndex: Int, inputFingerprint: String) {
        requireFingerprint(inputFingerprint)
        require(nextFrameIndex >= integratedFrames) { "Checkpoint would replay already integrated frames" }
        require(fingerprintBinding == null || fingerprintBinding == inputFingerprint) { "Checkpoint input fingerprint changed" }
        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { payload ->
            payload.writeUTF(inputFingerprint)
            payload.writeFloat(voxelSizeMeters)
            payload.writeInt(maxVoxels)
            payload.writeUTF(worldFrameId ?: "")
            payload.writeLong(lastTimestampNs)
            payload.writeUTF(lastFrameId)
            payload.writeInt(integratedFrames)
            payload.writeInt(nextFrameIndex)
            payload.writeBoolean(reachedCapacity)
            payload.writeInt(cells.size)
            for ((key, cell) in cells) {
                payload.writeInt(key.x); payload.writeInt(key.y); payload.writeInt(key.z)
                payload.writeDouble(cell.x); payload.writeDouble(cell.y); payload.writeDouble(cell.z)
                payload.writeDouble(cell.weight)
                payload.writeDouble(cell.red); payload.writeDouble(cell.green); payload.writeDouble(cell.blue)
                payload.writeDouble(cell.colorWeight)
                payload.writeLong(cell.samples); payload.writeLong(cell.colorSamples)
            }
        }
        val bytes = payloadBuffer.toByteArray()
        check(bytes.size <= MAX_PAYLOAD_BYTES) { "Checkpoint payload limit exceeded" }
        val envelope = DataOutputStream(output)
        envelope.writeInt(MAGIC)
        envelope.writeInt(VERSION)
        envelope.writeInt(bytes.size)
        envelope.write(bytes)
        envelope.write(MessageDigest.getInstance("SHA-256").digest(bytes))
        envelope.flush()
        fingerprintBinding = inputFingerprint
    }

    companion object {
        private const val MAGIC = 0x52434631
        private const val VERSION = 1
        private const val MIN_CONFIDENCE = 128
        private const val MAX_VOXELS = 1_000_000
        private const val MAX_PAYLOAD_BYTES = 96 * 1024 * 1024
        private const val CELL_BYTES = 92

        private fun requireFingerprint(value: String) {
            require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "Input fingerprint must be lowercase SHA-256" }
        }

        fun restoreCheckpoint(
            input: InputStream, inputFingerprint: String,
            unprojector: DepthUnprojector = CpuDepthUnprojector(),
        ): FusionCheckpoint {
            requireFingerprint(inputFingerprint)
            val envelope = DataInputStream(input)
            require(envelope.readInt() == MAGIC && envelope.readInt() == VERSION) { "Unsupported reconstruction checkpoint" }
            val length = envelope.readInt()
            require(length in 1..MAX_PAYLOAD_BYTES) { "Checkpoint payload exceeds allocation bound" }
            val bytes = ByteArray(length)
            envelope.readFully(bytes)
            val checksum = ByteArray(32)
            envelope.readFully(checksum)
            require(envelope.read() == -1) { "Unexpected checkpoint trailing data" }
            require(MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(bytes))) { "Checkpoint integrity mismatch" }
            val payload = DataInputStream(ByteArrayInputStream(bytes))
            require(payload.readUTF() == inputFingerprint) { "Checkpoint input fingerprint mismatch" }
            val fusion = VoxelFusion(payload.readFloat(), payload.readInt(), unprojector)
            val world = payload.readUTF()
            val timestamp = payload.readLong()
            val lastId = payload.readUTF()
            val integratedFrames = payload.readInt()
            val nextFrame = payload.readInt()
            val capacity = payload.readUnsignedByte()
            val count = payload.readInt()
            require(world.length <= 256 && lastId.length <= 256 && timestamp >= -1) { "Invalid checkpoint frame identity" }
            require(integratedFrames >= 0 && nextFrame >= integratedFrames) { "Invalid checkpoint resume position" }
            require(capacity in 0..1 && count in 0..fusion.maxVoxels) { "Invalid checkpoint voxel count" }
            require(payload.available().toLong() == count.toLong() * CELL_BYTES) { "Checkpoint voxel payload length mismatch" }
            if (integratedFrames == 0) {
                require(world.isEmpty() && lastId.isEmpty() && timestamp == -1L && count == 0 && capacity == 0) { "Invalid empty checkpoint" }
            } else {
                require(world.isNotBlank() && lastId.isNotBlank() && timestamp >= 0) { "Missing checkpoint world/frame identity" }
            }
            require(capacity == 0 || count == fusion.maxVoxels) { "Invalid checkpoint capacity state" }
            repeat(count) {
                val key = Key(payload.readInt(), payload.readInt(), payload.readInt())
                val cell = Cell(payload.readDouble(), payload.readDouble(), payload.readDouble(), payload.readDouble(),
                    payload.readDouble(), payload.readDouble(), payload.readDouble(), payload.readDouble(),
                    payload.readLong(), payload.readLong())
                require(cell.samples <= integratedFrames.toLong() * MAX_IMAGE_PIXELS) { "Impossible checkpoint observation count" }
                validateCell(key, cell, fusion.voxelSizeMeters)
                require(fusion.cells.put(key, cell) == null) { "Duplicate checkpoint voxel" }
            }
            fusion.worldFrameId = world.ifEmpty { null }
            fusion.lastTimestampNs = timestamp
            fusion.lastFrameId = lastId
            fusion.integratedFrames = integratedFrames
            fusion.reachedCapacity = capacity == 1
            fusion.fingerprintBinding = inputFingerprint
            return FusionCheckpoint(fusion, nextFrame)
        }

        private fun validateCell(key: Key, cell: Cell, voxelSize: Float) {
            require(listOf(cell.x, cell.y, cell.z, cell.weight, cell.red, cell.green, cell.blue, cell.colorWeight).all { it.isFinite() }) {
                "Non-finite checkpoint voxel"
            }
            require(cell.samples > 0 && cell.colorSamples in 0..cell.samples && cell.weight > 0 && cell.colorWeight >= 0) {
                "Invalid checkpoint voxel support"
            }
            val epsilon = cell.samples.toDouble() * 1e-7
            require(cell.weight <= cell.samples + epsilon && cell.weight >= cell.samples * (MIN_CONFIDENCE / 255.0) - epsilon) {
                "Invalid checkpoint confidence weight"
            }
            require(cell.colorWeight <= cell.weight + epsilon && cell.colorWeight <= cell.colorSamples + epsilon &&
                cell.colorWeight >= cell.colorSamples * (MIN_CONFIDENCE / 255.0) - epsilon) { "Invalid checkpoint colour weight" }
            if (cell.colorSamples == 0L) require(cell.colorWeight == 0.0) { "Unsupported checkpoint colour" }
            require(listOf(cell.red, cell.green, cell.blue).all { it >= 0 && it <= 255 * cell.colorWeight + epsilon }) { "Invalid checkpoint colour" }
            val means = doubleArrayOf(cell.x / cell.weight, cell.y / cell.weight, cell.z / cell.weight)
            val coordinates = intArrayOf(key.x, key.y, key.z)
            for (axis in 0..2) {
                val low = coordinates[axis].toDouble() * voxelSize
                val high = (coordinates[axis].toDouble() + 1) * voxelSize
                val tolerance = maxOf(1e-8, abs(low) * 1e-12)
                require(means[axis] >= low - tolerance && means[axis] <= high + tolerance) { "Checkpoint voxel position/key mismatch" }
            }
        }
    }
}
