package com.example.docscanner.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.docscanner.cv.PageProcessor
import com.example.docscanner.model.ScannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ExportManager {

    private const val PAGE_W = 595   // A4 宽（pt，72dpi）
    private const val PAGE_H = 842   // A4 高

    // ---------- 多页合并 PDF ----------

    suspend fun exportPdf(
        context: Context,
        pages: List<ScannedPage>,
        onProgress: (done: Int, total: Int) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext null

        val pdf = PdfDocument()
        var success = true

        for ((index, page) in pages.withIndex()) {
            val bitmap = PageProcessor.renderForExport(page)
            if (bitmap == null) {
                success = false
                break
            }
            try {
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create()
                val pdfPage = pdf.startPage(pageInfo)

                // fitCenter 到 A4
                val scale = minOf(
                    PAGE_W.toFloat() / bitmap.width,
                    PAGE_H.toFloat() / bitmap.height
                )
                val drawW = bitmap.width * scale
                val drawH = bitmap.height * scale
                val left = (PAGE_W - drawW) / 2f
                val top = (PAGE_H - drawH) / 2f

                pdfPage.canvas.drawColor(Color.WHITE)
                pdfPage.canvas.drawBitmap(
                    bitmap, null, RectF(left, top, left + drawW, top + drawH), null
                )
                pdf.finishPage(pdfPage)
            } finally {
                bitmap.recycle()
            }
            onProgress(index + 1, pages.size)
        }

        if (!success) {
            pdf.close()
            return@withContext null
        }

        val uri = writeToMediaStore(
            context = context,
            fileName = "scan_${System.currentTimeMillis()}.pdf",
            mimeType = "application/pdf",
            relativeDir = Environment.DIRECTORY_DOWNLOADS + "/DocScanner"
        ) { out ->
            pdf.writeTo(out)
        }
        pdf.close()
        uri
    }

    // ---------- 批量导出图片 ----------

    suspend fun exportImages(
        context: Context,
        pages: List<ScannedPage>,
        onProgress: (done: Int, total: Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        for ((index, page) in pages.withIndex()) {
            val bitmap = PageProcessor.renderForExport(page) ?: continue
            try {
                writeToMediaStore(
                    context = context,
                    fileName = "scan_${System.currentTimeMillis()}_${index + 1}.jpg",
                    mimeType = "image/jpeg",
                    relativeDir = Environment.DIRECTORY_PICTURES + "/DocScanner"
                ) { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                count++
            } finally {
                bitmap.recycle()
            }
            onProgress(index + 1, pages.size)
        }
        count
    }

    // ---------- MediaStore 写入 ----------

    private inline fun writeToMediaStore(
        context: Context,
        fileName: String,
        mimeType: String,
        relativeDir: String,
        write: (java.io.OutputStream) -> Unit
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            }
            val collection = when {
                mimeType == "application/pdf" -> MediaStore.Files.getContentUri("external")
                else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = context.contentResolver.insert(collection, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use(write)
            uri
        } else {
            val baseDir = when {
                mimeType == "application/pdf" ->
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                else ->
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            }
            val dir = File(baseDir, "DocScanner").apply { mkdirs() }
            val file = File(dir, fileName)
            try {
                FileOutputStream(file).use(write)
                Uri.fromFile(file)
            } catch (e: Exception) {
                null
            }
        }
    }
}
