package com.example.roomscan.geometry

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 一个墙角点在 ARCore 世界坐标系中的位置(单位:米)。
 *
 * ARCore 的世界坐标系是右手系、Y 轴朝上(重力反方向)。
 * 平面图只关心水平面,所以我们把 (x, z) 投影到 2D,忽略 y(高度)。
 * 由于 ARCore 通过 VIO(视觉 + IMU 融合)恢复真实米制尺度,
 * 这里的坐标直接就是真实世界的米,不需要任何参照物。
 */
@Parcelize
data class Corner(val x: Float, val y: Float, val z: Float) : Parcelable {
    /** 投影到水平面。注意 2D 平面图用 (x, z) 作为 (east, north)。 */
    fun to2D(): Point2 = Point2(x.toDouble(), z.toDouble())
}

data class Point2(val x: Double, val y: Double) {
    fun distanceTo(o: Point2): Double = hypot(o.x - x, o.y - y)
}

/** 一面墙:从角点 i 到角点 i+1,带长度(米)。 */
data class Wall(val from: Point2, val to: Point2) {
    val length: Double get() = from.distanceTo(to)
    val midpoint: Point2 get() = Point2((from.x + to.x) / 2, (from.y + to.y) / 2)
}

/**
 * 由一圈墙角点构成的封闭多边形房间。
 */
class FloorPlan(corners: List<Corner>) {

    val points: List<Point2> = corners.map { it.to2D() }

    /** 封闭多边形的所有墙(最后一点连回第一点)。 */
    val walls: List<Wall> = if (points.size < 2) emptyList() else
        points.indices.map { i -> Wall(points[i], points[(i + 1) % points.size]) }

    val perimeter: Double get() = walls.sumOf { it.length }

    /** 鞋带公式(Shoelace formula)算多边形面积,单位 m²。 */
    val area: Double
        get() {
            if (points.size < 3) return 0.0
            var s = 0.0
            for (i in points.indices) {
                val p = points[i]
                val q = points[(i + 1) % points.size]
                s += p.x * q.y - q.x * p.y
            }
            return abs(s) / 2.0
        }

    /** 轴对齐包围盒,用于把图缩放到画布上。 */
    val bounds: Bounds
        get() {
            if (points.isEmpty()) return Bounds(0.0, 0.0, 1.0, 1.0)
            return Bounds(
                minX = points.minOf { it.x },
                minY = points.minOf { it.y },
                maxX = points.maxOf { it.x },
                maxY = points.maxOf { it.y },
            )
        }

    val isValid: Boolean get() = points.size >= 3

    data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        val width: Double get() = (maxX - minX).coerceAtLeast(0.01)
        val height: Double get() = (maxY - minY).coerceAtLeast(0.01)
    }

    companion object {
        /**
         * 可选的"直角吸附":真实房间墙面绝大多数是互相垂直的,
         * 用户点角点时的手抖会让 90° 变成 87°。开启后,把每面墙的方向
         * 吸附到最接近的 0/90/180/270 度(相对第一面墙),并重算角点。
         * 这是一个常见的提精度技巧;若房间有斜墙请关闭。
         */
        fun squareUp(corners: List<Corner>, toleranceDeg: Double = 12.0): List<Corner> {
            if (corners.size < 3) return corners
            val pts = corners.map { it.to2D() }
            // 第一面墙的方向作为参考轴
            val ref = Math.atan2(pts[1].y - pts[0].y, pts[1].x - pts[0].x)
            val out = mutableListOf(pts[0])
            for (i in 1 until pts.size) {
                val prev = out[i - 1]
                val cur = pts[i]
                val dx = cur.x - prev.x
                val dy = cur.y - prev.y
                val len = hypot(dx, dy)
                val ang = Math.atan2(dy, dx) - ref
                // 相对参考轴的角度,吸附到最近的 90° 倍数
                val snapped = Math.round(ang / (Math.PI / 2)) * (Math.PI / 2)
                val diffDeg = Math.toDegrees(abs(ang - snapped))
                if (diffDeg <= toleranceDeg) {
                    val a = snapped + ref
                    out.add(Point2(prev.x + len * Math.cos(a), prev.y + len * Math.sin(a)))
                } else {
                    out.add(cur) // 真的是斜墙,保留原值
                }
            }
            return out.mapIndexed { i, p -> Corner(p.x.toFloat(), corners[i].y, p.y.toFloat()) }
        }
    }
}
