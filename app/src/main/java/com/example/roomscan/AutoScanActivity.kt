package com.example.roomscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.roomscan.databinding.ActivityAutoScanBinding
import com.example.roomscan.geometry.RangeScanSolver
import com.example.roomscan.vision.FloorBoundaryDetector
import java.util.concurrent.Executors
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.tan

/**
 * 自动扫描(实验):站在房间中央,举着手机原地慢慢转一圈,自动出平面图。
 *
 * 原理:
 *  1. 旋转矢量传感器给出每一帧相机的朝向(方位角)和下倾角(pitch)。
 *  2. FloorBoundaryDetector 在画面中央找到"地板-墙交线"所在的行。
 *  3. 已知相机高度 h 和该行对应的视线下倾角 φ,距离 = h / tan(φ)。
 *  4. 每个方位角累积距离 → 360° 扫描 → RangeScanSolver 拟合多边形。
 *
 * 唯一需要用户给的参数:相机高度(默认 1.45 m,大致是手机举在胸口/眼前的高度)。
 */
class AutoScanActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityAutoScanBinding
    private lateinit var sensorManager: SensorManager
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val detector = FloorBoundaryDetector()
    private val solver = RangeScanSolver()

    // 传感器给出的相机姿态(在传感器线程写、分析线程读;都是基本类型,volatile 够用)
    @Volatile private var headingDeg = 0f
    @Volatile private var pitchRad = 0.0        // 相机光轴下倾角,>0 表示镜头朝下
    @Volatile private var rollDeg = 0.0
    @Volatile private var scanning = false
    private val rot = FloatArray(9)

    // 分析图像的垂直焦距(像素),由相机参数估算;默认按 65° 垂直视场
    private var focalPx = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        binding.btnScan.setOnClickListener {
            scanning = !scanning
            binding.btnScan.text = getString(if (scanning) R.string.btn_pause_scan else R.string.btn_start_scan)
            binding.hintText.text = getString(if (scanning) R.string.auto_hint_scanning else R.string.auto_hint_idle)
            binding.overlay.scanning = scanning
        }
        binding.btnReset.setOnClickListener { solver.clear(); binding.overlay.radar = solver.medians(); binding.overlay.invalidate() }
        binding.btnFinish.setOnClickListener { finishScan() }
        binding.heightInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                s?.toString()?.toFloatOrNull()?.takeIf { it in 0.5f..2.5f }?.let { cameraHeightM = it }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 2)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startCamera()
        else { Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show(); finish() }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9).build()
                .also { it.setSurfaceProvider(binding.preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(360, 640))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true) // 让帧和屏幕方向一致(竖屏)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { img -> analyze(img) }
            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)

            // 用相机物理参数估算垂直视场:focal_mm / sensorLongSide_mm
            try {
                val info = Camera2CameraInfo.from(camera.cameraInfo)
                val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                if (focal != null && size != null) {
                    // 竖屏时画面的"高"对应传感器的长边(通常是 width)
                    val longSideMm = maxOf(size.width, size.height)
                    focalRatio = focal / longSideMm // 焦距 / 长边 → 乘以画面高度像素得 focalPx
                }
            } catch (_: Throwable) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private var focalRatio = -1f

    private fun analyze(img: ImageProxy) {
        try {
            val w = img.width; val h = img.height
            if (focalPx < 0) {
                focalPx = if (focalRatio > 0) focalRatio * h else (h / 2f) / tan(Math.toRadians(65.0 / 2)).toFloat()
            }
            val cy = h / 2f
            val pitch = pitchRad
            // 地平线所在行:视线下倾 pitch 时,水平方向对应的像素行在中心上方 f*tan(pitch)
            val horizonRow = cy - focalPx * tan(pitch).toFloat()

            val plane = img.planes[0]
            val res = detector.detect(plane.buffer, w, h, plane.rowStride, minRow = horizonRow.toInt() + 2)

            var dist = Float.NaN
            if (res.boundaryRow > 0 && res.confidence >= 0.5f && kotlin.math.abs(rollDeg) < 20) {
                // 该行视线相对光轴的角度 + 相机本身的下倾角 = 对交线的总下倾角
                val depression = pitch + atan(((res.boundaryRow - cy) / focalPx).toDouble())
                dist = RangeScanSolver.distanceFromDepression(cameraHeight(), depression)
                if (scanning && !dist.isNaN()) solver.add(headingDeg, dist)
            }

            val hFrac = horizonRow / h
            val bFrac = if (res.boundaryRow > 0) res.boundaryRow / h.toFloat() else -1f
            val radar = solver.medians()
            runOnUiThread {
                binding.overlay.apply {
                    horizonFrac = hFrac; boundaryFrac = bFrac; distanceM = dist
                    confidence = res.confidence; headingDeg = this@AutoScanActivity.headingDeg
                    this.radar = radar; invalidate()
                }
            }
        } finally {
            img.close()
        }
    }

    @Volatile private var cameraHeightM = 1.45f
    private fun cameraHeight(): Float = cameraHeightM

    private fun finishScan() {
        val cov = solver.coverage()
        if (cov < 0.5f) {
            Toast.makeText(this, getString(R.string.auto_need_coverage, (cov * 100).toInt()), Toast.LENGTH_LONG).show()
            return
        }
        val sol = solver.solve()
        if (sol == null || sol.corners.size < 3) {
            Toast.makeText(this, R.string.auto_solve_failed, Toast.LENGTH_LONG).show()
            return
        }
        scanning = false
        val intent = Intent(this, FloorPlanActivity::class.java)
        intent.putParcelableArrayListExtra(FloorPlanActivity.EXTRA_CORNERS, ArrayList(sol.corners))
        intent.putExtra(FloorPlanActivity.EXTRA_SQUARE_UP, false) // solver 已做主轴吸附
        startActivity(intent)
    }

    // ---------------- 传感器:算相机朝向与下倾 ----------------

    override fun onResume() {
        super.onResume()
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        s?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }
    override fun onDestroy() { super.onDestroy(); analysisExecutor.shutdown() }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // R 把设备坐标变到世界坐标(x 东, y 北, z 上)。后置摄像头沿设备 -Z 看出去。
        val dx = -rot[2]; val dy = -rot[5]; val dz = -rot[8]
        headingDeg = ((Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())) + 360) % 360).toFloat()
        pitchRad = -asin(dz.toDouble().coerceIn(-1.0, 1.0))   // 镜头朝下 → dz<0 → pitch>0
        // 手机"上边"方向(设备 +Y)在世界里的 z 分量接近 1 表示竖直握持;用其与竖直的偏离近似 roll
        val upz = rot[7].toDouble()
        rollDeg = Math.toDegrees(Math.acos(upz.coerceIn(-1.0, 1.0))) - Math.toDegrees(pitchRad).let { kotlin.math.abs(it) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
