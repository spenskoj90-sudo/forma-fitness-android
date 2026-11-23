package ru.forma.app

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** A small, bounded glTF subset: the exact original Infinix GLB, NOT exercise instruction. */
internal object FormaConceptDecoder {
    data class Mesh(val positions: FloatBuffer, val normals: FloatBuffer, val indices: ShortBuffer,
                    val count: Int, val rgba: FloatArray)
    data class Scene(val meshes: List<Mesh>)

    fun decode(bytes: ByteArray): Scene {
        require(FormaConceptGlbContract.valid(bytes)) { "Original GLB digest or container invalid" }
        val whole = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLength = whole.getInt(12)
        val json = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8).trim())
        val extras = json.getJSONObject("extras")
        require(extras.getString("review_status") == "CONCEPT_ONLY")
        require(!extras.getBoolean("technique_verified"))
        require(!extras.getBoolean("medical_accuracy_verified"))
        require(json.getJSONObject("asset").getString("version") == "2.0")
        val bins = json.getJSONArray("buffers")
        require(bins.length() == 1 && !bins.getJSONObject(0).has("uri"))
        val binStart = 28 + jsonLength
        val binSize = whole.getInt(20 + jsonLength)
        require(bins.getJSONObject(0).getInt("byteLength") <= binSize)
        val views = json.getJSONArray("bufferViews")
        val accessors = json.getJSONArray("accessors")
        val mats = json.getJSONArray("materials")
        val meshes = json.getJSONArray("meshes")
        require(views.length() <= 100 && accessors.length() <= 100)
        require(meshes.length() == 20 && mats.length() == 3)

        fun range(index: Int, count: Int, type: String, component: Int, stride: Int): Int {
            require(index in 0 until accessors.length())
            val a = accessors.getJSONObject(index)
            require(a.getString("type") == type && a.getInt("componentType") == component)
            require(a.getInt("count") == count && !a.has("sparse"))
            val vi = a.getInt("bufferView")
            require(vi in 0 until views.length())
            val v = views.getJSONObject(vi)
            require(v.optInt("buffer", 0) == 0 && !v.has("byteStride"))
            val within = a.optInt("byteOffset", 0)
            val viewStart = v.optInt("byteOffset", 0)
            val size = v.getInt("byteLength")
            val amount = count.toLong() * stride.toLong()
            require(count in 1..8000 && within >= 0 && viewStart >= 0 && size >= amount)
            require(within.toLong() + amount <= size.toLong())
            require(viewStart.toLong() + size <= binSize.toLong())
            val offset = binStart.toLong() + viewStart + within
            require(offset >= binStart && offset + amount <= bytes.size.toLong())
            return offset.toInt()
        }

        fun floatBuffer(accessor: Int, count: Int): FloatBuffer {
            val start = range(accessor, count, "VEC3", 5126, 12)
            val data = ByteBuffer.allocateDirect(count * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until count * 3) {
                val value = whole.getFloat(start + 4 * i)
                require(value.isFinite() && value in -100f..100f)
                data.put(value)
            }
            data.position(0)
            return data
        }

        var totalTriangles = 0
        val decoded = ArrayList<Mesh>(meshes.length())
        for (i in 0 until meshes.length()) {
            val item = meshes.getJSONObject(i)
            val primitives = item.getJSONArray("primitives")
            require(primitives.length() == 1)
            val p = primitives.getJSONObject(0)
            require(p.getInt("mode") == GLES20.GL_TRIANGLES)
            val attributes = p.getJSONObject("attributes")
            val positionIndex = attributes.getInt("POSITION")
            val vertexCount = accessors.getJSONObject(positionIndex).getInt("count")
            require(vertexCount in 3..3000)
            val positions = floatBuffer(positionIndex, vertexCount)
            val normals = floatBuffer(attributes.getInt("NORMAL"), vertexCount)
            val indexAccessor = p.getInt("indices")
            val indexCount = accessors.getJSONObject(indexAccessor).getInt("count")
            require(indexCount in 3..10000 && indexCount % 3 == 0)
            val idxStart = range(indexAccessor, indexCount, "SCALAR", 5123, 2)
            val indices = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (k in 0 until indexCount) {
                val value = whole.getShort(idxStart + 2 * k)
                require((value.toInt() and 0xffff) < vertexCount)
                indices.put(value)
            }
            indices.position(0)
            val matIndex = p.getInt("material")
            require(matIndex in 0 until mats.length())
            val factors = mats.getJSONObject(matIndex)
                .getJSONObject("pbrMetallicRoughness").getJSONArray("baseColorFactor")
            require(factors.length() == 4)
            val color = FloatArray(4) { j -> factors.getDouble(j).toFloat() }
            require(color.all { it.isFinite() && it in 0f..1f })
            decoded.add(Mesh(positions, normals, indices, indexCount, color))
            totalTriangles += indexCount / 3
        }
        require(totalTriangles == 5004) { "Unexpected original geometry" }
        return Scene(decoded)
    }
}

private class FormaConceptRenderer(private val scene: FormaConceptDecoder.Scene) : GLSurfaceView.Renderer {
    @Volatile var angle = 0f
    @Volatile var distance = 3.8f
    private var program = 0
    private var positionSlot = 0
    private var normalSlot = 0
    private var matrixSlot = 0
    private var rotationSlot = 0
    private var colorSlot = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val rotation = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "OpenGL shader compilation failed" }
        return shader
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, """
            attribute vec3 aPos;
            attribute vec3 aNormal;
            uniform mat4 uMvp;
            uniform mat4 uRotation;
            varying vec3 vNormal;
            void main() {
                vNormal = mat3(uRotation) * aNormal;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """.trimIndent())
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float;
            uniform vec4 uColor;
            varying vec3 vNormal;
            void main() {
                float light = 0.48 + 0.52 * abs(dot(normalize(vNormal), normalize(vec3(0.45, 0.7, 0.55))));
                gl_FragColor = vec4(uColor.rgb * light, uColor.a);
            }
        """.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "OpenGL program linking failed" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        positionSlot = GLES20.glGetAttribLocation(program, "aPos")
        normalSlot = GLES20.glGetAttribLocation(program, "aNormal")
        matrixSlot = GLES20.glGetUniformLocation(program, "uMvp")
        rotationSlot = GLES20.glGetUniformLocation(program, "uRotation")
        colorSlot = GLES20.glGetUniformLocation(program, "uColor")
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glClearColor(0.07f, 0.095f, 0.13f, 1f)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 45f, width.toFloat() / height.coerceAtLeast(1), 0.1f, 30f)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        Matrix.setLookAtM(view, 0, 0f, 1.0f, distance, 0f, 0.9f, 0f, 0f, 1f, 0f)
        Matrix.setRotateM(rotation, 0, angle, 0f, 1f, 0f)
        Matrix.multiplyMM(modelView, 0, view, 0, rotation, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        GLES20.glUniformMatrix4fv(matrixSlot, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(rotationSlot, 1, false, rotation, 0)
        GLES20.glEnableVertexAttribArray(positionSlot)
        GLES20.glEnableVertexAttribArray(normalSlot)
        for (mesh in scene.meshes) {
            mesh.positions.position(0)
            mesh.normals.position(0)
            mesh.indices.position(0)
            GLES20.glVertexAttribPointer(positionSlot, 3, GLES20.GL_FLOAT, false, 0, mesh.positions)
            GLES20.glVertexAttribPointer(normalSlot, 3, GLES20.GL_FLOAT, false, 0, mesh.normals)
            GLES20.glUniform4fv(colorSlot, 1, mesh.rgba, 0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.count, GLES20.GL_UNSIGNED_SHORT, mesh.indices)
        }
        GLES20.glDisableVertexAttribArray(normalSlot)
        GLES20.glDisableVertexAttribArray(positionSlot)
    }
}

private class FormaConceptSurface(context: Context, scene: FormaConceptDecoder.Scene) : GLSurfaceView(context) {
    private val graphics = FormaConceptRenderer(scene)
    private var lastX = 0f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(graphics)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun rotateBy(degrees: Float) {
        graphics.angle += degrees
        requestRender()
    }

    fun zoomBy(delta: Float) {
        graphics.distance = (graphics.distance + delta).coerceIn(2.5f, 7f)
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount != 1) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; return true }
            MotionEvent.ACTION_MOVE -> {
                rotateBy((event.x - lastX) * 0.55f)
                lastX = event.x
                return true
            }
        }
        return true
    }
}

/** Accessible, opt-in renderer; it never labels the mannequin as reviewed exercise technique. */
@Composable
internal fun FormaConceptViewer() {
    val context = LocalContext.current
    val scene = remember(context) {
        runCatching {
            context.assets.open(FormaConceptGlbContract.ASSET_PATH).use { stream ->
                val bytes = stream.readBytes()
                FormaConceptDecoder.decode(bytes)
            }
        }.getOrNull()
    }
    val supportsEs2 = remember(context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.deviceConfigurationInfo.reqGlEsVersion >= 0x20000
        }.getOrDefault(false)
    }
    if (scene == null || !supportsEs2) {
        Text("3D-концепт недоступен: файл не прошёл проверку либо OpenGL ES 2.0 не поддерживается.")
        return
    }
    val surface = remember(context, scene) { FormaConceptSurface(context, scene) }
    DisposableEffect(surface) {
        surface.onResume()
        onDispose { surface.onPause() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Экспериментальная объёмная модель. Не является инструкцией по упражнениям или медицинской анатомией.",
            style = MaterialTheme.typography.bodySmall)
        AndroidView(factory = { surface },
            modifier = Modifier.fillMaxWidth().height(290.dp)
                .testTag("forma3d-concept-surface")
                .semantics { contentDescription = "Экспериментальная 3D-фигура. Можно повернуть пальцем или кнопками." })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { surface.rotateBy(-25f) },
                modifier = Modifier.weight(1f).testTag("forma3d-rotate-left")) { Text("↶") }
            Button(onClick = { surface.rotateBy(25f) },
                modifier = Modifier.weight(1f).testTag("forma3d-rotate-right")) { Text("↷") }
            Button(onClick = { surface.zoomBy(-0.35f) },
                modifier = Modifier.weight(1f).testTag("forma3d-zoom-in")) { Text("+") }
            Button(onClick = { surface.zoomBy(0.35f) },
                modifier = Modifier.weight(1f).testTag("forma3d-zoom-out")) { Text("−") }
        }
    }
}
