package com.example.roomscan.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 叠在相机预览上的调试/反馈层:
 *  - 地平线(虚线)
 *  - 检测到的地板-墙交线(黄色横线)+ 距离
 *  - 右上角小雷达:已累积的 360° 距离扫描
 */
class ScanOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 0..1 的比例,-1 表示无 */
    var horizonFrac = -1f
    var boundaryFrac = -1f
    var distanceM = Float.NaN
    var confidence = 0f
    var headingDeg = 0f
    var radar: FloatArray = FloatArray(360) { Float.NaN }
    var scanning = false

    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2f; style = Paint.Style.STROKE; alpha = 160
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val boundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD54F"); strokeWidth = 6f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val radarBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = 150 }
    private val radarRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 90; style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val radarDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#64B5F6") }
    private val radarHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD54F"); strokeWidth = 3f }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val bandL = w * 0.35f; val bandR = w * 0.65f

        if (horizonFrac in 0f..1f) {
            val y = horizonFrac * h
            canvas.drawLine(0f, y, w, y, horizonPaint)
            canvas.drawText("地平线", 12f, y - 8f, smallText)
        }
        if (boundaryFrac in 0f..1f) {
            val y = boundaryFrac * h
            boundaryPaint.alpha = (120 + 135 * confidence).toInt().coerceIn(0, 255)
            canvas.drawLine(bandL, y, bandR, y, boundaryPaint)
            canvas.drawLine(bandL, y - 14, bandL, y + 14, boundaryPaint)
            canvas.drawLine(bandR, y - 14, bandR, y + 14, boundaryPaint)
            if (!distanceM.isNaN()) canvas.drawText(String.format(Locale.US, "%.2f m", distanceM), w / 2, y - 16f, textPaint)
        } else {
            canvas.drawText("没找到地板边缘", w / 2, h * 0.6f, textPaint)
        }

        // ---- 雷达 ----
        val R = min(w, h) * 0.22f
        val cx = w - R - 20f; val cy = R + 20f + 110f
        canvas.drawCircle(cx, cy, R + 6f, radarBg)
        canvas.drawCircle(cx, cy, R, radarRing)
        canvas.drawCircle(cx, cy, R / 2, radarRing)
        var maxR = 1f
        radar.forEach { if (!it.isNaN() && it > maxR) maxR = it }
        maxR = (maxR * 1.1f).coerceAtLeast(2f)
        for (i in 0 until 360) {
            val r = radar[i]; if (r.isNaN()) continue
            val a = Math.toRadians(i.toDouble())
            val px = cx + (r / maxR * R) * sin(a).toFloat()
            val py = cy - (r / maxR * R) * cos(a).toFloat()
            canvas.drawCircle(px, py, 3f, radarDot)
        }
        // 当前朝向
        val ha = Math.toRadians(headingDeg.toDouble())
        canvas.drawLine(cx, cy, cx + R * sin(ha).toFloat(), cy - R * cos(ha).toFloat(), radarHead)
        canvas.drawCircle(cx, cy, 4f, radarHead)
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText(String.format(Locale.US, "外圈 %.0f m", maxR), cx, cy + R + 30f, smallText)
        val cov = radar.count { !it.isNaN() } * 100 / 360
        canvas.drawText("覆盖 $cov%", cx, cy + R + 58f, smallText)
        smallText.textAlign = Paint.Align.LEFT
    }
}
