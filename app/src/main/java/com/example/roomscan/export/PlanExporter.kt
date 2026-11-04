package com.example.roomscan.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.roomscan.geometry.FloorPlan
import com.example.roomscan.view.FloorPlanPainter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把平面图导出为 PNG 或 PDF,并通过系统分享面板发出去(微信/邮件/保存到文件)。
 * 复用 FloorPlanPainter,保证导出与屏幕显示一致。
 */
object PlanExporter {

    private const val PNG_SIZE = 2048 // 导出 2048×2048 高清图

    fun exportPng(ctx: Context, plan: FloorPlan): File {
        val bmp = Bitmap.createBitmap(PNG_SIZE, PNG_SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        FloorPlanPainter.draw(c, plan, PNG_SIZE.toFloat(), PNG_SIZE.toFloat())
        val f = newFile(ctx, "png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    fun exportPdf(ctx: Context, plan: FloorPlan): File {
        // A4 竖版,单位 1/72 inch(PdfDocument 的点)
        val pageW = 595; val pageH = 842
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)
        // 顶部留 60pt 给标题
        val title = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 18f; color = Color.BLACK
        }
        c.drawText("RoomScan 平面图  " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()), 36f, 40f, title)
        c.save()
        c.translate(0f, 60f)
        FloorPlanPainter.draw(c, plan, pageW.toFloat(), (pageH - 60).toFloat())
        c.restore()
        doc.finishPage(page)
        val f = newFile(ctx, "pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun share(ctx: Context, file: File, mime: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    private fun newFile(ctx: Context, ext: String): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "floorplan_$stamp.$ext")
    }
}
