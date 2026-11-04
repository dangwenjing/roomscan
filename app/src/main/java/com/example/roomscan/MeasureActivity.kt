package com.example.roomscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.roomscan.databinding.ActivityMeasureBinding
import com.example.roomscan.databinding.DialogLengthBinding
import com.example.roomscan.geometry.Traverse
import com.example.roomscan.geometry.WallMeasure
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 测量模式(不依赖 ARCore,任何安卓机都能用)。
 *
 * 用法:
 *   站在一个墙角,像拍照一样把手机对准 **沿着这面墙、指向下一个墙角** 的方向,
 *   按【锁定方向】→ 输入这面墙的长度(激光尺/卷尺量)→ 走到下一个墙角重复。
 *   走完一圈按【完成】。
 *
 * 方向来自 GAME_ROTATION_VECTOR(陀螺仪融合,不用磁力计——室内磁场干扰大,
 * 而我们只需要相对方向,不需要真北)。长度由用户测量输入,所以精度 = 你量尺的精度。
 */
class MeasureActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMeasureBinding
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private val walls = mutableListOf<WallMeasure>()

    // 当前方位角(度,0..360),用 sin/cos 做低通滤波避免 359↔0 跳变
    private var filtSin = 0.0
    private var filtCos = 1.0
    private var haveHeading = false
    private val rotMat = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeasureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            Toast.makeText(this, R.string.no_rotation_sensor, Toast.LENGTH_LONG).show()
        }

        binding.btnLock.setOnClickListener { onLockWall() }
        binding.btnUndo.setOnClickListener { if (walls.isNotEmpty()) { walls.removeAt(walls.size - 1); refresh() } }
        binding.btnReset.setOnClickListener { walls.clear(); refresh() }
        binding.snapSwitch.setOnCheckedChangeListener { _, _ -> refresh() }
        binding.btnFinish.setOnClickListener { onFinish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        // 相机只是辅助瞄准,没权限也能用(靠手机朝向)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.preview.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                // 相机不可用时静默降级,界面仍可用
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // ---------------- 传感器 ----------------

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotMat, event.values)
        // 手机竖直握持、摄像头朝前(像拍照):把设备坐标系重映射,使 azimuth 表示摄像头指向
        SensorManager.remapCoordinateSystem(rotMat, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val az = orientation[0].toDouble() // 弧度,-π..π
        val a = 0.15
        filtSin = filtSin * (1 - a) + sin(az) * a
        filtCos = filtCos * (1 - a) + cos(az) * a
        haveHeading = true
        updateHeadingUi()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun currentHeadingDeg(): Float {
        var d = Math.toDegrees(atan2(filtSin, filtCos))
        if (d < 0) d += 360.0
        return d.toFloat()
    }

    private fun updateHeadingUi() {
        val h = currentHeadingDeg()
        if (walls.isEmpty()) {
            binding.headingText.text = String.format(Locale.US, "%.0f°", h)
            binding.headingSub.text = getString(R.string.heading_absolute)
        } else {
            // 相对第一面墙的转角更直观:一般房间应该是 0 / 90 / 180 / 270
            val rel = Traverse.normalize((h - walls[0].headingDeg).toDouble())
            binding.headingText.text = String.format(Locale.US, "%+.0f°", rel)
            binding.headingSub.text = getString(R.string.heading_relative)
        }
    }

    // ---------------- 交互 ----------------

    private fun onLockWall() {
        if (!haveHeading) { Toast.makeText(this, R.string.no_rotation_sensor, Toast.LENGTH_SHORT).show(); return }
        val heading = currentHeadingDeg()
        val dlg = DialogLengthBinding.inflate(layoutInflater)
        dlg.dialogHeading.text = getString(R.string.dialog_heading_fmt, walls.size + 1, heading)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_length_title)
            .setView(dlg.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val len = dlg.lengthInput.text?.toString()?.trim()?.toFloatOrNull()
                if (len == null || len <= 0f) {
                    Toast.makeText(this, R.string.invalid_length, Toast.LENGTH_SHORT).show()
                } else {
                    walls.add(WallMeasure(heading, len))
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dlg.lengthInput.requestFocus()
    }

    private fun refresh() {
        val sb = StringBuilder()
        walls.forEachIndexed { i, w ->
            val rel = if (i == 0) 0.0 else Traverse.normalize((w.headingDeg - walls[0].headingDeg).toDouble())
            sb.append(String.format(Locale.US, "墙%-2d  转角 %+4.0f°   长 %6.2f m\n", i + 1, rel, w.lengthM))
        }
        binding.wallList.text = if (sb.isEmpty()) getString(R.string.no_walls_yet) else sb.toString().trimEnd()

        binding.hintText.text = getString(
            when {
                walls.isEmpty() -> R.string.measure_hint_first
                walls.size < 3 -> R.string.measure_hint_next
                else -> R.string.measure_hint_done
            }
        )

        // 实时显示闭合差,给用户一个"量得准不准"的反馈
        if (walls.size >= 3) {
            val r = Traverse.toCorners(walls, snapRight = binding.snapSwitch.isChecked, adjust = false)
            binding.closureText.text = getString(R.string.closure_fmt, r.closureError * 100)
        } else binding.closureText.text = ""
        updateHeadingUi()
    }

    private fun onFinish() {
        if (walls.size < 3) { Toast.makeText(this, R.string.need_three, Toast.LENGTH_SHORT).show(); return }
        val r = Traverse.toCorners(walls, snapRight = binding.snapSwitch.isChecked, adjust = true)
        if (r.closureError > 0.5) {
            // 闭合差超过 50 cm,基本是某面墙长度输错或方向没对准,提醒一下但不阻止
            Toast.makeText(this, getString(R.string.closure_warning, r.closureError), Toast.LENGTH_LONG).show()
        }
        val intent = Intent(this, FloorPlanActivity::class.java)
        intent.putParcelableArrayListExtra(FloorPlanActivity.EXTRA_CORNERS, ArrayList(r.corners))
        intent.putExtra(FloorPlanActivity.EXTRA_CLOSURE, r.closureError)
        intent.putExtra(FloorPlanActivity.EXTRA_SQUARE_UP, false) // Traverse 已做吸附,不要再吸一次
        startActivity(intent)
    }
}
