package com.example.docscanner.edit

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.docscanner.cv.PageProcessor
import com.example.docscanner.model.FilterMode
import com.example.docscanner.model.ScannedPage
import com.example.docscanner.model.ScanSession
import io.github.itshedimisawi.vitro.Glass
import io.github.itshedimisawi.vitro.glassBackdrop
import io.github.itshedimisawi.vitro.glassEffect
import io.github.itshedimisawi.vitro.rememberGlassBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PAGE_ID = "page_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pageId = intent.getStringExtra(EXTRA_PAGE_ID) ?: run { finish(); return }
        setContent { EditScreen(pageId) }
    }
}

@Composable
private fun EditScreen(pageId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backdrop = rememberGlassBackdrop()
    val cacheDir = context.cacheDir

    val initial = remember(pageId) { ScanSession.find(pageId) }

    var page by remember(pageId) { mutableStateOf(initial) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(true) }
    var showCrop by remember { mutableStateOf(false) }
    var cropPreview by remember { mutableStateOf<Bitmap?>(null) }
    var cropSourceWidth by remember { mutableStateOf(0) }

    // 首次进入：自动裁剪 + 渲染预览
    LaunchedEffect(pageId) {
        val p = initial ?: return@LaunchedEffect
        busy = true
        val auto = PageProcessor.autoCropIfPossible(p, cacheDir)
        ScanSession.update(auto)
        page = auto
        preview = PageProcessor.renderPreview(auto)
        busy = false
    }

    // 旋转/滤镜变化时重渲染预览
    LaunchedEffect(page?.workingPath, page?.quarterTurns, page?.filterMode) {
        val p = page ?: return@LaunchedEffect
        if (busy) return@LaunchedEffect
        val old = preview
        preview = PageProcessor.renderPreview(p)
        old?.recycle()
    }

    Box(modifier = Modifier.fillMaxSize().glassBackdrop(backdrop)) {
        preview?.let { bmp ->
            ZoomablePreview(
                bitmap = bmp,
                quarterTurns = page?.quarterTurns ?: 0,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 底部操作栏
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .glassEffect(backdrop, Glass.Regular.interactive()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextButton(onClick = {
                page = page?.copy(quarterTurns = ((page!!.quarterTurns + 1) % 4))
                page?.let { ScanSession.update(it) }
            }) { Text("旋转") }

            TextButton(onClick = {
                val p = page ?: return@TextButton
                scope.launch {
                    busy = true
                    // 先物化旋转，保证裁切界面与最终方向一致
                    val materialized = if (p.quarterTurns % 4 != 0) {
                        withContext(Dispatchers.Default) {
                            val src = PageProcessor.decodeSampled(p.workingPath, 3508) ?: p
                            if (src is ScannedPage) p else {
                                val rotated = PageProcessor.rotate(src, p.quarterTurns)
                                val f = File(cacheDir, "scan/rot_${p.id}_${System.currentTimeMillis()}.jpg")
                                f.parentFile?.mkdirs()
                                java.io.FileOutputStream(f).use {
                                    rotated.compress(Bitmap.CompressFormat.JPEG, 92, it)
                                }
                                if (rotated !== src) rotated.recycle()
                                src.recycle()
                                p.copy(workingPath = f.absolutePath, quarterTurns = 0)
                            }
                        }
                    } else p
                    ScanSession.update(materialized)
                    page = materialized

                    val cropBmp = PageProcessor.decodeSampled(materialized.workingPath, 1600)
                    cropPreview = cropBmp
                    cropSourceWidth = cropBmp?.width ?: 0
                    showCrop = cropBmp != null
                    busy = false
                }
            }) { Text("裁切") }

            TextButton(onClick = {
                page = page?.copy(
                    filterMode = when (page!!.filterMode) {
                        FilterMode.ORIGINAL -> FilterMode.ENHANCE
                        FilterMode.ENHANCE -> FilterMode.BINARY
                        FilterMode.BINARY -> FilterMode.ORIGINAL
                    }
                )
                page?.let { ScanSession.update(it) }
            }) {
                Text(
                    when (page?.filterMode) {
                        FilterMode.ORIGINAL -> "原图"
                        FilterMode.ENHANCE -> "增强"
                        FilterMode.BINARY -> "黑白"
                        null -> "滤镜"
                    }
                )
            }
        }

        // 顶部完成 / 删除
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
                .glassEffect(backdrop, Glass.Regular.interactive()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = {
                // 完成：清空缩略图，主页会重新生成
                page?.let { ScanSession.update(it.copy(thumbnailPath = null)) }
                (context as? android.app.Activity)?.finish()
            }) { Text("完成") }

            TextButton(onClick = {
                ScanSession.remove(pageId)
                (context as? android.app.Activity)?.finish()
            }) { Text("删除") }
        }
    }

    if (showCrop && cropPreview != null) {
        CropDialog(
            bitmap = cropPreview!!,
            onConfirm = { corners ->
                showCrop = false
                val p = page ?: return@CropDialog
                scope.launch {
                    busy = true
                    val cropped = PageProcessor.cropPage(p, corners, cropSourceWidth, cacheDir)
                    ScanSession.update(cropped)
                    page = cropped
                    val old = preview
                    preview = PageProcessor.renderPreview(cropped)
                    old?.recycle()
                    cropPreview?.recycle()
                    cropPreview = null
                    busy = false
                }
            },
            onDismiss = {
                showCrop = false
                cropPreview?.recycle()
                cropPreview = null
            }
        )
    }
}
