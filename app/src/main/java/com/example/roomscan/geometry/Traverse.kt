package com.example.roomscan.geometry

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 测量模式(不依赖 ARCore)的数据模型。
 *
 * 用户沿墙走一圈,每面墙记录两件事:
 *   - headingDeg:手机指向该墙方向时的水平方位角(来自陀螺仪/旋转矢量传感器),单位度
 *   - lengthM:  该墙的长度(用激光尺/卷尺量后手动输入),单位米
 *
 * 这在测绘学里叫"导线测量"(traverse):从起点出发,按方向+距离逐段推算坐标。
 * 走完一圈理论上应该回到起点;实际会有一个"闭合差",用经典的 Bowditch(罗盘法)
 * 平差按边长比例把误差分摊掉,得到严格闭合的多边形。
 */
@Parcelize
data class WallMeasure(val headingDeg: Float, val lengthM: Float) : Parcelable

object Traverse {

    /**
     * 把一圈墙推算成角点坐标(相对第一面墙起点,单位米)。
     *
     * @param walls      按行走顺序的墙
     * @param snapRight  是否把相对第一面墙接近 0/90/180/270° 的方向吸附成直角(容差 [snapTolDeg])
     * @param adjust     是否做 Bowditch 闭合平差
     * @return 角点列表(每面墙的起点);size == walls.size
     */
    fun toCorners(
        walls: List<WallMeasure>,
        snapRight: Boolean = true,
        snapTolDeg: Double = 12.0,
        adjust: Boolean = true,
    ): Result {
        if (walls.size < 3) return Result(emptyList(), 0.0, 0.0)

        // 1) 方向归一化到相对第一面墙,并可选直角吸附
        val ref = walls[0].headingDeg.toDouble()
        val headings = walls.map { w ->
            var rel = normalize(w.headingDeg - ref)
            if (snapRight) {
                val snapped = (rel / 90.0).roundToInt() * 90.0
                if (abs(normalize(rel - snapped)) <= snapTolDeg) rel = snapped
            }
            rel
        }

        // 2) 每面墙的位移向量。数学约定:x 向东,y 向北,方位角从北顺时针 → dx = L·sin, dy = L·cos
        val dx = DoubleArray(walls.size)
        val dy = DoubleArray(walls.size)
        for (i in walls.indices) {
            val a = Math.toRadians(headings[i])
            dx[i] = walls[i].lengthM * sin(a)
            dy[i] = walls[i].lengthM * cos(a)
        }

        // 3) 闭合差:所有位移之和应为 0
        val ex = dx.sum()
        val ey = dy.sum()
        val closure = hypot(ex, ey)
        val perimeter = walls.sumOf { it.lengthM.toDouble() }

        // 4) Bowditch 平差:每条边按其长度占周长的比例分摊闭合差
        if (adjust && perimeter > 0) {
            for (i in walls.indices) {
                val k = walls[i].lengthM / perimeter
                dx[i] -= ex * k
                dy[i] -= ey * k
            }
        }

        // 5) 累加得到角点
        val corners = ArrayList<Corner>(walls.size)
        var x = 0.0; var y = 0.0
        for (i in walls.indices) {
            corners.add(Corner(x.toFloat(), 0f, y.toFloat()))
            x += dx[i]; y += dy[i]
        }
        return Result(corners, closure, perimeter)
    }

    /** 把角度归一化到 (-180, 180] */
    fun normalize(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    data class Result(
        val corners: List<Corner>,
        /** 平差前的闭合差(米)。越小说明量得越准,>0.3 m 基本说明某面墙输错了。 */
        val closureError: Double,
        val perimeter: Double,
    )
}
