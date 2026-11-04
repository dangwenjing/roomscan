package com.example.roomscan.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 把"转一圈"得到的 360° 距离扫描 r(θ)(穷人版激光雷达)拟合成房间多边形。
 *
 * 流程:每度一个桶取中位数 → 插值补小缺口 → 中值滤波 → 转成 2D 点 →
 *       Douglas-Peucker 简化成折线 → 找主方向并把接近直角的边吸附 → 相邻边求交点 = 墙角。
 */
class RangeScanSolver {

    private val bins = Array(360) { ArrayList<Float>(8) }

    @Synchronized
    fun add(headingDeg: Float, distanceM: Float) {
        if (distanceM.isNaN() || distanceM < 0.3f || distanceM > 15f) return
        var h = headingDeg.roundToInt() % 360
        if (h < 0) h += 360
        val list = bins[h]
        if (list.size >= 24) list.removeAt(0)
        list.add(distanceM)
    }

    @Synchronized
    fun clear() { bins.forEach { it.clear() } }

    /** 每度的中位距离,NaN 表示没数据。 */
    @Synchronized
    fun medians(): FloatArray = FloatArray(360) { i ->
        val l = bins[i]
        if (l.size < 2) Float.NaN else { val s = l.sorted(); s[s.size / 2] }
    }

    /** 有数据的方向占比 0..1 */
    fun coverage(): Float = medians().count { !it.isNaN() } / 360f

    data class Solution(val corners: List<Corner>, val coverage: Float, val points: List<Point2>)

    fun solve(): Solution? {
        val m = medians()
        val cov = m.count { !it.isNaN() } / 360f
        if (cov < 0.5f) return null

        // 1) 离群点剔除:与局部中位数(窗口 9)相差 >15% 的方向视为误检
        val local = medianFilter(m, 4)
        val clean = FloatArray(360) { i ->
            val v = m[i]; val l = local[i]
            if (v.isNaN() || l.isNaN() || abs(v - l) / l >= 0.15f) Float.NaN else v
        }
        // 2) 补缺口:线性插值跨越 ≤ 40° 的空洞(门、被家具挡住的地方)
        val filled = interpolateGaps(clean, 40)
        // 3) 中值滤波(窗口 7)
        val smooth = medianFilter(filled, 3)
        // 4) 极坐标 → 直角坐标(θ 为方位角,从北顺时针;x 向东,y 向北)
        val pts = ArrayList<Point2>()
        for (i in 0 until 360) {
            val r = smooth[i]; if (r.isNaN()) continue
            val a = Math.toRadians(i.toDouble())
            pts.add(Point2(r * sin(a), r * cos(a)))
        }
        if (pts.size < 30) return null

        // 5) 闭合折线简化
        val meanR = smooth.filter { !it.isNaN() }.average()
        val tol = maxOf(0.15, meanR * 0.06)
        var poly = simplifyClosed(pts, tol)
        if (poly.size < 3) return null

        // 6) 主方向 + 直角吸附 + 合并共线边 + 删掉碎边
        poly = snapToAxes(poly, snapTolDeg = 20.0)
        if (poly.size < 3) return null

        val corners = poly.map { Corner(it.x.toFloat(), 0f, it.y.toFloat()) }
        return Solution(corners, cov, pts)
    }

    // ---------------------------------------------------------------- helpers

    private fun interpolateGaps(m: FloatArray, maxGap: Int): FloatArray {
        val out = m.copyOf()
        val n = 360
        var i = 0
        while (i < n) {
            if (!m[i].isNaN()) { i++; continue }
            // 找这个空洞的长度
            var j = i
            while (j < i + n && m[j % n].isNaN()) j++
            val len = j - i
            if (len >= n) return out
            if (len <= maxGap) {
                val a = m[(i - 1 + n) % n]; val b = m[j % n]
                if (!a.isNaN() && !b.isNaN()) for (k in 0 until len) out[(i + k) % n] = a + (b - a) * (k + 1) / (len + 1)
            }
            i = j
        }
        return out
    }

    /** 闭合折线的 Douglas-Peucker:先按离起点最远的点切成两段,各自 DP。 */
    private fun simplifyClosed(pts: List<Point2>, tol: Double): List<Point2> {
        var far = 0; var fd = -1.0
        for (i in pts.indices) { val d = pts[0].distanceTo(pts[i]); if (d > fd) { fd = d; far = i } }
        val a = dp(pts.subList(0, far + 1), tol)
        val b = dp(pts.subList(far, pts.size) + pts[0], tol)
        return (a + b.subList(1, b.size - 1)).distinct()
    }

    private fun dp(p: List<Point2>, tol: Double): List<Point2> {
        if (p.size < 3) return p
        var idx = 0; var dmax = 0.0
        val a = p.first(); val b = p.last()
        for (i in 1 until p.size - 1) { val d = pointLineDist(p[i], a, b); if (d > dmax) { dmax = d; idx = i } }
        return if (dmax > tol) dp(p.subList(0, idx + 1), tol).dropLast(1) + dp(p.subList(idx, p.size), tol)
        else listOf(a, b)
    }

    private fun pointLineDist(p: Point2, a: Point2, b: Point2): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy); if (len < 1e-9) return p.distanceTo(a)
        return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
    }

    /**
     * 找主方向(边长加权的 mod-90° 直方图峰值),把方向与主轴差 < snapTol 的边吸附到主轴,
     * 合并相邻共线边,再用相邻边直线求交点得到角点。
     */
    private class Seg(var ang: Double, val mid: Point2, val len: Double)

    private fun medianFilter(f: FloatArray, half: Int): FloatArray = FloatArray(360) { i ->
        val w = (-half..half).map { f[(i + it + 360) % 360] }.filter { !it.isNaN() }.sorted()
        if (w.isEmpty()) Float.NaN else w[w.size / 2]
    }

    /** 合并相邻且方向几乎相同的边(长度加权中点),首尾也检查一次。 */
    private fun mergePass(segs: List<Seg>): ArrayList<Seg> {
        val merged = ArrayList<Seg>()
        fun join(a: Seg, b: Seg): Seg {
            val w = a.len + b.len
            return Seg(a.ang, Point2((a.mid.x * a.len + b.mid.x * b.len) / w, (a.mid.y * a.len + b.mid.y * b.len) / w), w)
        }
        for (s in segs) {
            val last = merged.lastOrNull()
            if (last != null && abs(normalizeRad(last.ang - s.ang)) < Math.toRadians(8.0)) merged[merged.size - 1] = join(last, s)
            else merged.add(s)
        }
        if (merged.size >= 2 && abs(normalizeRad(merged.first().ang - merged.last().ang)) < Math.toRadians(8.0)) {
            merged[0] = join(merged.first(), merged.last()); merged.removeAt(merged.size - 1)
        }
        return merged
    }

    private fun snapToAxes(poly: List<Point2>, snapTolDeg: Double): List<Point2> {
        val n = poly.size
        var segs = ArrayList<Seg>()
        for (i in 0 until n) {
            val a = poly[i]; val b = poly[(i + 1) % n]
            segs.add(Seg(atan2(b.y - a.y, b.x - a.x), Point2((a.x + b.x) / 2, (a.y + b.y) / 2), a.distanceTo(b)))
        }
        // 主方向:对 4θ 做长度加权向量平均(等价于 mod 90° 的圆均值)
        var sx = 0.0; var sy = 0.0
        segs.forEach { sx += it.len * cos(4 * it.ang); sy += it.len * sin(4 * it.ang) }
        val main = atan2(sy, sx) / 4.0
        val tol = Math.toRadians(snapTolDeg)
        segs.forEach { s ->
            val k = Math.round((s.ang - main) / (PI / 2))
            val target = main + k * (PI / 2)
            if (abs(normalizeRad(s.ang - target)) <= tol) s.ang = target
        }
        segs = mergePass(segs)

        // 删掉碎边(误检留下的小台阶):每次删最短的一条,再合并一次,直到都够长
        val perimeter = segs.sumOf { it.len }
        val minLen = maxOf(0.4, 0.07 * perimeter)
        while (segs.size > 4) {
            val i = segs.indices.minByOrNull { segs[it].len } ?: break
            if (segs[i].len >= minLen) break
            segs.removeAt(i)
            segs = mergePass(segs)
        }
        if (segs.size < 3) return emptyList()

        // 相邻边直线求交点 = 墙角
        val out = ArrayList<Point2>()
        for (i in segs.indices) {
            val a = segs[(i - 1 + segs.size) % segs.size]; val b = segs[i]
            out.add(intersect(a.mid, a.ang, b.mid, b.ang) ?: b.mid)
        }
        return out
    }

    private fun intersect(p1: Point2, a1: Double, p2: Point2, a2: Double): Point2? {
        val d1x = cos(a1); val d1y = sin(a1); val d2x = cos(a2); val d2y = sin(a2)
        val den = d1x * d2y - d1y * d2x
        if (abs(den) < 1e-6) return null
        val t = ((p2.x - p1.x) * d2y - (p2.y - p1.y) * d2x) / den
        return Point2(p1.x + d1x * t, p1.y + d1y * t)
    }

    private fun normalizeRad(a: Double): Double {
        var r = a % (2 * PI)
        if (r > PI) r -= 2 * PI
        if (r <= -PI) r += 2 * PI
        return r
    }

    companion object {
        /** 相机高度 h、视线下倾角 depression(弧度)→ 到交线的水平距离 */
        fun distanceFromDepression(cameraHeightM: Float, depressionRad: Double): Float {
            if (depressionRad <= Math.toRadians(3.0)) return Float.NaN // 几乎水平,距离发散
            return (cameraHeightM / Math.tan(depressionRad)).toFloat()
        }
        fun sq(x: Double) = x * x
        fun norm(x: Double, y: Double) = sqrt(sq(x) + sq(y))
    }
}
