package com.example.roomscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.roomscan.databinding.ActivityMainBinding
import com.example.roomscan.geometry.Corner
import com.example.roomscan.rendering.BackgroundRenderer
import com.example.roomscan.rendering.OverlayRenderer
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 扫描界面。
 *
 * 工作流程:
 *  1. ARCore 启动后用户缓慢扫地面,ARCore 检测出水平平面(地板)。
 *  2. 用户把屏幕中心准星对准"地板与墙的交角",按【添加角点】。
 *     我们对屏幕中心做 hitTest,取命中地板平面的 3D 点 → 这就是一个带真实米制坐标的墙角。
 *  3. 绕房间一圈点完所有墙角后按【完成】,跳到 FloorPlanActivity 生成平面图。
 *
 * 精度来源:ARCore 的 VIO(相机 + IMU)给出米制尺度,因此不需要任何参照物。
 * 典型误差在 1–3 cm / 米量级,取决于光照、纹理和用户扫描是否平稳。
 */
class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var binding: ActivityMainBinding
    private var session: Session? = null
    private var installRequested = false

    private val background = BackgroundRenderer()
    private val overlay = OverlayRenderer()

    // 已采集的角点。写在 UI 线程(点击),读在 GL 线程(渲染)→ 用同步容器。
    private val corners = java.util.Collections.synchronizedList(mutableListOf<Corner>())

    // UI 线程请求"在下一帧对屏幕中心做 hitTest"
    private val addRequested = AtomicBoolean(false)

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var hasPlane = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        binding.btnAdd.setOnClickListener { addRequested.set(true) }
        binding.btnUndo.setOnClickListener {
            if (corners.isNotEmpty()) corners.removeAt(corners.size - 1)
            refreshCount()
        }
        binding.btnReset.setOnClickListener { corners.clear(); refreshCount() }
        binding.btnFinish.setOnClickListener {
            if (corners.size < 3) {
                Toast.makeText(this, R.string.need_three, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, FloorPlanActivity::class.java)
            intent.putParcelableArrayListExtra(FloorPlanActivity.EXTRA_CORNERS, ArrayList(corners))
            startActivity(intent)
        }
    }

    // ---------------- ARCore session lifecycle ----------------

    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                // 如设备缺少 ARCore 服务,引导用户去 Play 商店安装
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
                    return
                }
                session = Session(this).also { s ->
                    val config = Config(s).apply {
                        // 只检测水平面(地板),忽略墙面平面,避免 hitTest 打到墙上
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        focusMode = Config.FocusMode.AUTO
                        // 若设备支持 Depth API,开启后 hitTest 更稳(自动降级)
                        if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            depthMode = Config.DepthMode.AUTOMATIC
                        }
                    }
                    s.configure(config)
                }
            } catch (e: UnavailableException) {
                Toast.makeText(this, R.string.arcore_unavailable, Toast.LENGTH_LONG).show()
                finish(); return
            } catch (e: Exception) {
                Toast.makeText(this, "ARCore 初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
                finish(); return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "相机被占用", Toast.LENGTH_LONG).show()
            session = null; return
        }
        binding.surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        // 顺序很重要:先停 GL 渲染,再暂停 session,否则 update() 可能抛异常
        binding.surfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isEmpty() || results[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ---------------- GLSurfaceView.Renderer (GL 线程) ----------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        overlay.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(windowManager.defaultDisplay.rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        s.setCameraTextureName(background.textureId)

        val frame: Frame = try { s.update() } catch (e: CameraNotAvailableException) { return }
        background.draw(frame)

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            updateHint(getString(R.string.hint_scanning)); return
        }

        // 是否已经检测到地板平面
        val planes = s.getAllTrackables(Plane::class.java)
        hasPlane = planes.any { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        updateHint(getString(if (hasPlane) R.string.hint_aim else R.string.hint_scanning))

        // 处理"添加角点"请求:对屏幕中心做 hitTest
        if (addRequested.getAndSet(false)) {
            val hit = frame.hitTest(viewportWidth / 2f, viewportHeight / 2f).firstOrNull { h ->
                val t = h.trackable
                // 只接受命中"朝上的水平平面"且落点在该平面的多边形范围内
                t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(h.hitPose)
                        // 兜底:若开启了 Depth,允许命中深度点
                        || t is com.google.ar.core.DepthPoint
            }
            if (hit != null) {
                val p = hit.hitPose
                corners.add(Corner(p.tx(), p.ty(), p.tz()))
                runOnUiThread { refreshCount() }
            } else {
                runOnUiThread { Toast.makeText(this, R.string.hint_no_hit, Toast.LENGTH_SHORT).show() }
            }
        }

        // 画已采集的角点与墙线
        val view = FloatArray(16); val proj = FloatArray(16); val vp = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, 0.1f, 100f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        overlay.draw(vp, ArrayList(corners), closeLoop = corners.size >= 3)
    }

    // ---------------- UI helpers ----------------

    private var lastHint: String? = null
    private fun updateHint(text: String) {
        if (text == lastHint) return
        lastHint = text
        runOnUiThread { binding.hintText.text = text }
    }

    private fun refreshCount() {
        binding.cornerCount.text = getString(R.string.corner_count, corners.size)
    }
}
