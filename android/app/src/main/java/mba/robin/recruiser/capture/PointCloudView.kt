package mba.robin.recruiser.capture

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Saved observed points are drawn directly: no interpolated walls or closed holes. */
class PointCloudView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private var points: FloatBuffer? = null
    private var count = 0
    private var program = 0
    private var aspect = 1f
    private val center = FloatArray(3)
    private var radius = 2f
    private var yaw = 0.5f
    private var pitch = 0.3f
    private var distance = 5f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            queueEvent { distance = (distance / detector.scaleFactor).coerceIn(radius * 0.1f, radius * 30f) }
            requestRender(); return true
        }
    })

    init {
        setEGLContextClientVersion(2); setRenderer(this); renderMode = RENDERMODE_WHEN_DIRTY
        contentDescription = "Observed room point cloud. Drag to orbit, pinch to zoom, drag with two fingers to pan."
    }

    /** File reading belongs on the caller's background worker. */
    fun load(file: File): Int {
        var vertices = 0
        val loaded = file.bufferedReader().use { reader ->
            require(reader.readLine() == "ply" && reader.readLine() == "format ascii 1.0") { "Only the saved ASCII coloured PLY is supported here" }
            while (true) {
                val line = reader.readLine() ?: error("Incomplete PLY header")
                if (line.startsWith("element vertex ")) vertices = line.substringAfterLast(' ').toInt()
                if (line == "end_header") break
            }
            require(vertices in 1..250_000) { "Invalid saved point count" }
            val buffer = ByteBuffer.allocateDirect(vertices * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val minimum = FloatArray(3) { Float.POSITIVE_INFINITY }; val maximum = FloatArray(3) { Float.NEGATIVE_INFINITY }
            repeat(vertices) {
                val tokens = (reader.readLine() ?: error("Truncated saved PLY")).trim().split(Regex("\\s+"))
                require(tokens.size == 6) { "Invalid saved PLY point" }
                for (axis in 0..2) {
                    val value = tokens[axis].toFloat(); require(value.isFinite())
                    buffer.put(value); minimum[axis] = minOf(minimum[axis], value); maximum[axis] = maxOf(maximum[axis], value)
                }
                for (color in 3..5) { val value = tokens[color].toInt(); require(value in 0..255); buffer.put(value / 255f) }
            }
            Triple(buffer.apply { position(0) }, minimum, maximum)
        }
        val total = vertices
        queueEvent {
            points = loaded.first; count = total
            for (axis in 0..2) center[axis] = (loaded.second[axis] + loaded.third[axis]) / 2
            radius = max(0.2f, (0..2).maxOf { loaded.third[it] - loaded.second[it] })
            distance = radius * 2; yaw = 0.5f; pitch = 0.3f; panX = 0f; panY = 0f
        }
        requestRender()
        return vertices
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = GlPrograms.link("uniform mat4 mvp; attribute vec3 position; attribute vec3 color; varying vec3 vColor; void main(){gl_Position=mvp*vec4(position,1.0); gl_PointSize=3.0; vColor=color;}",
            "precision mediump float; varying vec3 vColor; void main(){vec2 p=gl_PointCoord-vec2(0.5); if(dot(p,p)>0.25)discard; gl_FragColor=vec4(vColor,1.0);}")
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glClearColor(0.035f, 0.055f, 0.075f, 1f)
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        aspect = width.toFloat() / max(1, height); GLES20.glViewport(0, 0, width, height)
    }
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val data = points ?: return
        val view = FloatArray(16); val projection = FloatArray(16); val matrix = FloatArray(16)
        val x = center[0] + panX; val y = center[1] + panY; val z = center[2]
        Matrix.setLookAtM(view, 0, x + distance * sin(yaw) * cos(pitch), y + distance * sin(pitch), z + distance * cos(yaw) * cos(pitch), x, y, z, 0f, 1f, 0f)
        Matrix.perspectiveM(projection, 0, 55f, aspect, max(0.005f, radius * 0.001f), max(100f, radius * 100f))
        Matrix.multiplyMM(matrix, 0, projection, 0, view, 0)
        GLES20.glUseProgram(program); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "mvp"), 1, false, matrix, 0)
        val position = GLES20.glGetAttribLocation(program, "position"); val color = GLES20.glGetAttribLocation(program, "color")
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(color)
        data.position(0); GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, data)
        data.position(3); GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 24, data)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(color)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX; val dy = event.y - lastY
                val pan = event.pointerCount > 1
                queueEvent {
                    if (pan) { panX -= dx * distance / max(1, width); panY += dy * distance / max(1, height) }
                    else { yaw -= dx * 0.008f; pitch = (pitch + dy * 0.008f).coerceIn(-1.5f, 1.5f) }
                }
                lastX = event.x; lastY = event.y; requestRender()
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
