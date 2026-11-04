package com.example.roomscan

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.roomscan.databinding.ActivityHomeBinding
import com.google.ar.core.ArCoreApk

/**
 * 入口:选择测量模式(任何安卓机)或 AR 扫描模式(需要 ARCore)。
 * 启动时异步检测 ARCore 可用性,不可用就把 AR 按钮置灰并说明原因。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnMeasure.setOnClickListener { startActivity(Intent(this, MeasureActivity::class.java)) }
        binding.btnAr.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        binding.btnAr.isEnabled = false
        binding.arStatus.text = getString(R.string.ar_checking)
    }

    override fun onResume() {
        super.onResume()
        checkArCore()
    }

    private fun checkArCore() {
        val availability = try { ArCoreApk.getInstance().checkAvailability(this) } catch (e: Throwable) {
            binding.arStatus.text = getString(R.string.ar_unsupported); return
        }
        if (availability.isTransient) {
            // 还在查询,稍后再问一次
            binding.root.postDelayed({ checkArCore() }, 300)
            return
        }
        when {
            availability.isSupported -> {
                binding.btnAr.isEnabled = true
                binding.arStatus.text = getString(
                    if (availability == ArCoreApk.Availability.SUPPORTED_INSTALLED) R.string.ar_ready
                    else R.string.ar_need_install
                )
            }
            else -> {
                binding.btnAr.isEnabled = false
                binding.arStatus.text = getString(R.string.ar_unsupported)
            }
        }
    }
}
