package com.example.docscanner.cv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import com.example.docscanner.model.FilterMode
import com.example.docscanner.model.ScannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object PageProcessor {

    private const val PREVIEW_MAX_DIM = 1600
    private const val THUMB_MAX_DIM = 480
    private const val EXPORT_MAX_DIM = 3508  // A4 @ 300dpi

    // ---------- 解码 ----------

    fun decodeSampled(path: String, maxDim: Int): Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    fun rotate(bitmap: Bitmap, quarterTurns: Int): Bitmap {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0) return bitmap
        val matrix = Matrix().apply { postRotate(turns * 90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ---------- 文件写入 ----------

    private fun writeJpeg(bitmap: Bitmap, dir: File, prefix: String, quality: Int = 92): File {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return file
    }

    // ---------- 自动裁剪 ----------

    /**
     * 首次进入编辑页时调用。检测成功则物化裁切，返回新的 page；失败原样返回。
     */
    suspend fun autoCropIfPossible(page: ScannedPage, cacheDir: File): ScannedPage =
        withContext(Dispatchers.Default) {
            if (page.autoCropAttempted) return@withContext page

            val bitmap = decodeSampled(page.workingPath, 1600)
                ?: return@withContext page.copy(autoCropAttempted = true)

            val corners = try {
                EdgeDetector.detectCorners(bitmap)
            } catch (e: Throwable) {
                null
            }

            if (corners == null) {
                bitmap.recycle()
                return@withContext page.copy(autoCropAttempted = true)
            }

            // 检测是在降采样图上做的，映射回全尺寸
            val full = BitmapFactory.decodeFile(page.workingPath)
                ?: run { bitmap.recycle(); return@withContext page.copy(autoCropAttempted = true) }
            val scaleX = full.width.toFloat() / bitmap.width
            val scaleY = full.height.toFloat() / bitmap.height
            val fullCorners = corners.map { PointF(it.x * scaleX, it.y * scaleY) }

            val cropped = try {
                PerspectiveCropper.crop(full, fullCorners)
            } catch (e: Throwable) {
                null
            }

            full.recycle()
            bitmap.recycle()

            if (cropped == null) {
                page.copy(autoCropAttempted = true)
            } else {
                val file = writeJpeg(cropped, File(cacheDir, "scan"), "auto_${page.id}")
                cropped.recycle()
                page.copy(
                    workingPath = file.absolutePath,
                    autoCropAttempted = true,
                    thumbnailPath = null
                )
            }
        }

    // ---------- 手动裁切 ----------

    /**
     * 先把当前旋转物化（保证裁切界面所见即所得），再对全尺寸图做透视裁剪。
     * @param previewCorners 相对降采样预览图的四点
     */
    suspend fun cropPage(
        page: ScannedPage,
        previewCorners: List<PointF>,
        previewWidth: Int,
        cacheDir: File
    ): ScannedPage = withContext(Dispatchers.Default) {
        // 1. 物化旋转
        var working = page
        if (page.quarterTurns % 4 != 0) {
            val rotated = decodeSampled(page.workingPath, EXPORT_MAX_DIM)
                ?: return@withContext page
            val rotatedFull = rotate(rotated, page.quarterTurns)
            val rotatedFile = writeJpeg(rotatedFull, File(cacheDir, "scan"), "rot_${page.id}")
            if (rotatedFull !== rotated) rotated.recycle()
            rotatedFull.recycle()
            working = page.copy(workingPath = rotatedFile.absolutePath, quarterTurns = 0)
        }

        // 2. 全尺寸解码
        val full = BitmapFactory.decodeFile(working.workingPath)
            ?: return@withContext working
        val scale = full.width.toFloat() / previewWidth
        val fullCorners = previewCorners.map { PointF(it.x * scale, it.y * scale) }

        val cropped = try {
            PerspectiveCropper.crop(full, fullCorners)
        } catch (e: Throwable) {
            null
        }
        full.recycle()

        if (cropped == null) {
            working
        } else {
            val file = writeJpeg(cropped, File(cacheDir, "scan"), "crop_${page.id}")
            cropped.recycle()
            working.copy(workingPath = file.absolutePath, thumbnailPath = null)
        }
    }

    // ---------- 预览渲染 ----------

    suspend fun renderPreview(page: ScannedPage): Bitmap? = withContext(Dispatchers.Default) {
        val base = decodeSampled(page.workingPath, PREVIEW_MAX_DIM) ?: return@withContext null
        val rotated = rotate(base, page.quarterTurns)
        val filtered = DocumentFilter.apply(rotated, page.filterMode)
        if (filtered !== rotated) rotated.recycle()
        if (base !== rotated) base.recycle()
        filtered
    }

    // ---------- 缩略图 ----------

    suspend fun generateThumbnail(page: ScannedPage, cacheDir: File): String? =
        withContext(Dispatchers.Default) {
            val bitmap = renderPreview(page) ?: return@withContext null
            val scale = THUMB_MAX_DIM.toFloat() / max(bitmap.width, bitmap.height)
            val thumb = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true
                ).also { bitmap.recycle() }
            } else bitmap

            val file = writeJpeg(thumb, File(cacheDir, "thumb"), "thumb_${page.id}", 85)
            thumb.recycle()
            file.absolutePath
        }

    // ---------- 导出渲染 ----------

    /**
     * 按导出尺寸渲染单页。调用方负责 recycle。
     */
    suspend fun renderForExport(page: ScannedPage): Bitmap? = withContext(Dispatchers.Default) {
        val base = decodeSampled(page.workingPath, EXPORT_MAX_DIM) ?: return@withContext null
        val rotated = rotate(base, page.quarterTurns)
        val filtered = DocumentFilter.apply(rotated, page.filterMode)
        if (filtered !== rotated) rotated.recycle()
        if (base !== rotated) base.recycle()
        filtered
    }
}
