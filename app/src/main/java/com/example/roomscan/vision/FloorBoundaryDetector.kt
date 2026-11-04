package com.example.roomscan.vision

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 在一帧 RGBA 画面里找"地板与墙的交线"在屏幕中央一条竖带内的行号。
 *
 * 思路(经典方法,不用神经网络):
 *  画面最底部一定是你脚前面的地板。以它的颜色为参考,沿中央几列从下往上走,
 *  颜色一直和地板相近就继续,连续若干行颜色突变就认为碰到了墙(或踢脚线),
 *  那一行就是交线。多列取中位数抗噪。
 *
 * 适用:地板颜色大体均匀、和墙颜色有差别的房间(绝大多数家居)。
 * 不适用:强花纹地砖、地板与墙同色、强反光。
 */
class FloorBoundaryDetector(
    /** 颜色差阈值(0–441,RGB 欧氏距离)。越大越不容易被地板纹理打断,但越容易"穿墙"。 */
    private val threshold: Float = 38f,
    /** 连续多少行超阈值才算碰到墙 */
    private val runLength: Int = 3,
    /** 中央竖带占画面宽度比例 */
    private val bandFraction: Float = 0.30f,
    private val numColumns: Int = 9,
) {
    data class Result(
        /** 交线所在行(0 = 顶部),-1 表示没找到 */
        val boundaryRow: Int,
        /** 0..1,各列结果的一致性 */
        val confidence: Float,
    )

    /**
     * @param rgba        RGBA_8888 像素缓冲(CameraX OUTPUT_IMAGE_FORMAT_RGBA_8888,已按屏幕方向旋转)
     * @param rowStride   每行字节数
     * @param minRow      只在此行以下搜索(传地平线行号,墙的交线一定在地平线以下)
     */
    fun detect(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, minRow: Int): Result {
        val x0 = (width * (0.5f - bandFraction / 2)).toInt().coerceAtLeast(2)
        val x1 = (width * (0.5f + bandFraction / 2)).toInt().coerceAtMost(width - 3)
        val step = ((x1 - x0) / (numColumns - 1)).coerceAtLeast(1)
        val rows = IntArray(numColumns)
        var n = 0
        var x = x0
        while (x <= x1 && n < numColumns) {
            val r = scanColumn(rgba, width, height, rowStride, x, minRow)
            if (r >= 0) rows[n++] = r
            x += step
        }
        if (n < 3) return Result(-1, 0f)
        val valid = rows.copyOf(n).sortedArray()
        val median = valid[n / 2]
        // 一致性:有多少列落在中位数 ±6% 画面高度内
        val tol = (height * 0.06f).toInt().coerceAtLeast(3)
        val agree = valid.count { abs(it - median) <= tol }
        return Result(median, agree.toFloat() / n)
    }

    /** 读取 (x,y) 周围 3×3 的平均颜色,降低噪声。 */
    private fun avgColor(buf: ByteBuffer, rowStride: Int, x: Int, y: Int, out: FloatArray) {
        var r = 0; var g = 0; var b = 0
        for (dy in -1..1) for (dx in -1..1) {
            val i = (y + dy) * rowStride + (x + dx) * 4
            r += buf.get(i).toInt() and 0xFF
            g += buf.get(i + 1).toInt() and 0xFF
            b += buf.get(i + 2).toInt() and 0xFF
        }
        out[0] = r / 9f; out[1] = g / 9f; out[2] = b / 9f
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dr = a[0] - b[0]; val dg = a[1] - b[1]; val db = a[2] - b[2]
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun scanColumn(buf: ByteBuffer, width: Int, height: Int, rowStride: Int, x: Int, minRow: Int): Int {
        val c = FloatArray(3)
        val ref = FloatArray(3)
        // 参考色:最底部 6 行的平均
        var cnt = 0
        for (y in height - 2 downTo (height - 7).coerceAtLeast(1)) {
            avgColor(buf, rowStride, x, y, c)
            ref[0] += c[0]; ref[1] += c[1]; ref[2] += c[2]; cnt++
        }
        if (cnt == 0) return -1
        val inv = 1f / cnt
        ref[0] = ref[0] * inv; ref[1] = ref[1] * inv; ref[2] = ref[2] * inv

        var run = 0
        var boundary = -1
        val stopRow = minRow.coerceIn(1, height - 2)
        var y = height - 8
        while (y > stopRow) {
            avgColor(buf, rowStride, x, y, c)
            if (dist(c, ref) > threshold) {
                if (run == 0) boundary = y
                run++
                if (run >= runLength) return boundary
            } else {
                run = 0
                // 参考色缓慢跟随,适应光照渐变(地板远处会变暗/变亮)
                ref[0] = ref[0] * 0.92f + c[0] * 0.08f
                ref[1] = ref[1] * 0.92f + c[1] * 0.08f
                ref[2] = ref[2] * 0.92f + c[2] * 0.08f
            }
            y--
        }
        return -1 // 一路到地平线都是"地板色",说明看不到墙(太远或同色)
    }
}
