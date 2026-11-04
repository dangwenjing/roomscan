package com.example.roomscan

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.roomscan.databinding.ActivityFloorPlanBinding
import com.example.roomscan.export.PlanExporter
import com.example.roomscan.geometry.Corner
import com.example.roomscan.geometry.FloorPlan
import java.util.Locale

/**
 * 显示生成的平面图,并提供 PNG / PDF 导出分享。
 * AR 模式和测量模式都把角点(米)交到这里,后面的流程完全一样。
 */
class FloorPlanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFloorPlanBinding
    private lateinit var plan: FloorPlan

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFloorPlanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val raw: List<Corner> = (if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra(EXTRA_CORNERS, Corner::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(EXTRA_CORNERS)) ?: emptyList()

        if (raw.size < 3) { finish(); return }

        // 直角吸附:把接近 90° 的墙角修正为 90°(AR 模式默认开;测量模式在 Traverse 里已处理)
        val squareUp = intent.getBooleanExtra(EXTRA_SQUARE_UP, true)
        val corners = if (squareUp) FloorPlan.squareUp(raw) else raw
        plan = FloorPlan(corners)

        binding.floorPlanView.plan = plan
        var stats = getString(R.string.stats_fmt, plan.perimeter, plan.area, plan.points.size)
        val closure = intent.getDoubleExtra(EXTRA_CLOSURE, -1.0)
        if (closure >= 0) stats += String.format(Locale.US, "\n闭合差 %.1f cm(已平差)", closure * 100)
        binding.statsText.text = stats

        binding.btnExportPng.setOnClickListener {
            runCatching { PlanExporter.exportPng(this, plan) }
                .onSuccess { PlanExporter.share(this, it, "image/png", getString(R.string.share_title)) }
                .onFailure { Toast.makeText(this, "导出失败: ${it.message}", Toast.LENGTH_LONG).show() }
        }
        binding.btnExportPdf.setOnClickListener {
            runCatching { PlanExporter.exportPdf(this, plan) }
                .onSuccess { PlanExporter.share(this, it, "application/pdf", getString(R.string.share_title)) }
                .onFailure { Toast.makeText(this, "导出失败: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    companion object {
        const val EXTRA_CORNERS = "corners"
        const val EXTRA_CLOSURE = "closure"
        const val EXTRA_SQUARE_UP = "squareUp"
    }
}
