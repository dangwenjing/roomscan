package com.example.roomscan.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.roomscan.geometry.FloorPlan
import com.example.roomscan.geometry.Point2
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 把 FloorPlan 画成传统建筑平面图样式:
 * 粗黑墙线 + 浅色填充 + 每面墙的尺寸标注 + 比例尺。
 *
 * 核心绘制在 [FloorPlanPainter.draw] 里,与 View 无关,
 * 这样导出 PNG/PDF 时可以直接复用同一套绘制代码,保证所见即所得。
 */
class FloorPlanView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var plan: FloorPlan? = null
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = plan ?: return
        FloorPlanPainter.draw(canvas, p, width.toFloat(), height.toFloat())
    }
}

object FloorPlanPainter {

    /**
     * 在 [w]×[h] 的画布上绘制平面图。
     * 自动计算缩放比例使房间居中并留出标注空间。
     */
    fun draw(canvas: Canvas, plan: FloorPlan, w: Float, h: Float) {
        if (!plan.isValid) return

        // 根据画布大小按比例取线宽和字号,这样导出高分辨率 PNG 也不会显得线太细
        val unit = min(w, h) / 400f
        val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeWidth = 5f * unit; strokeJoin = Paint.Join.MITER
        }
        val fillPaint = Paint().apply { color = Color.parseColor("#E3F2FD"); style = Paint.Style.FILL }
        val dimLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E88E5"); style = Paint.Style.STROKE; strokeWidth = 1.2f * unit
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0D47A1"); textSize = 11f * unit; textAlign = Paint.Align.CENTER
        }
        val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY; textSize = 10f * unit; strokeWidth = 2f * unit
        }

        // ---- 坐标映射:米 → 像素 ----
        val b = plan.bounds
        val margin = 60f * unit                           // 留给尺寸标注
        val scale = min((w - 2 * margin) / b.width, (h - 2 * margin) / b.height).toFloat()
        val offX = (w - b.width.toFloat() * scale) / 2f
        val offY = (h - b.height.toFloat() * scale) / 2f
        // 注意 Y 轴翻转:AR 的 z 向北为正,屏幕 y 向下为正
        fun mapX(x: Double) = offX + ((x - b.minX) * scale).toFloat()
        fun mapY(y: Double) = h - offY - ((y - b.minY) * scale).toFloat()

        // ---- 房间多边形 ----
        val path = Path()
        plan.points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(mapX(p.x), mapY(p.y)) else path.lineTo(mapX(p.x), mapY(p.y))
        }
        path.close()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, wallPaint)

        // ---- 每面墙的尺寸标注(放在墙外侧) ----
        val centroid = centroid(plan.points)
        plan.walls.forEach { wall ->
            val mx = mapX(wall.midpoint.x); val my = mapY(wall.midpoint.y)
            // 墙的法向,指向远离房间中心的一侧
            val dx = mapX(wall.to.x) - mapX(wall.from.x)
            val dy = mapY(wall.to.y) - mapY(wall.from.y)
            var nx = -dy; var ny = dx
            val len = max(1e-6f, kotlin.math.hypot(nx, ny))
            nx /= len; ny /= len
            val cx = mapX(centroid.x); val cy = mapY(centroid.y)
            if ((mx - cx) * nx + (my - cy) * ny < 0) { nx = -nx; ny = -ny }

            val offset = 22f * unit
            val lx = mx + nx * offset; val ly = my + ny * offset
            // 尺寸线(与墙平行,在外侧)
            val half = kotlin.math.hypot(dx, dy) / 2f
            val ux = dx / (2 * half); val uy = dy / (2 * half)
            canvas.drawLine(lx - ux * half, ly - uy * half, lx + ux * half, ly + uy * half, dimLine)
            // 两端的小刻度
            canvas.drawLine(lx - ux * half, ly - uy * half, mx - ux * half, my - uy * half, dimLine)
            canvas.drawLine(lx + ux * half, ly + uy * half, mx + ux * half, my + uy * half, dimLine)

            // 文字:旋转到与墙平行,保持可读(不倒置)
            val label = String.format(Locale.US, "%.2f m", wall.length)
            var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            if (angle > 90f || angle < -90f) angle += 180f
            canvas.save()
            canvas.translate(lx + nx * 9f * unit, ly + ny * 9f * unit)
            canvas.rotate(angle)
            val tw = textPaint.measureText(label)
            canvas.drawRect(RectF(-tw / 2 - 3 * unit, -textPaint.textSize, tw / 2 + 3 * unit, 3 * unit), labelBg)
            canvas.drawText(label, 0f, 0f, textPaint)
            canvas.restore()
        }

        // ---- 左下角比例尺(1 m) ----
        val sx = 16f * unit; val sy = h - 16f * unit
        canvas.drawLine(sx, sy, sx + scale, sy, scalePaint)
        canvas.drawLine(sx, sy - 4 * unit, sx, sy + 4 * unit, scalePaint)
        canvas.drawLine(sx + scale, sy - 4 * unit, sx + scale, sy + 4 * unit, scalePaint)
        scalePaint.style = Paint.Style.FILL
        canvas.drawText("1 m", sx + scale / 2 - scalePaint.measureText("1 m") / 2, sy - 6 * unit, scalePaint)

        // ---- 右下角面积 ----
        val areaText = String.format(Locale.US, "面积 %.2f m²", plan.area)
        scalePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(areaText, w - 16f * unit, h - 16f * unit, scalePaint)

        // ---- 北向箭头(右上,AR 的 +z 方向;仅示意) ----
        val ax = w - 30f * unit; val ay = 30f * unit
        canvas.drawLine(ax, ay + 14 * unit, ax, ay - 14 * unit, scalePaint)
        canvas.drawLine(ax, ay - 14 * unit, ax - 5 * unit, ay - 6 * unit, scalePaint)
        canvas.drawLine(ax, ay - 14 * unit, ax + 5 * unit, ay - 6 * unit, scalePaint)
    }

    private fun centroid(pts: List<Point2>): Point2 {
        var cx = 0.0; var cy = 0.0
        pts.forEach { cx += it.x; cy += it.y }
        return Point2(cx / pts.size, cy / pts.size)
    }
}
