package com.example.docscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.docscanner.edit.EditActivity
import com.example.docscanner.export.ExportManager
import com.example.docscanner.model.ScanSession
import com.example.docscanner.scan.CameraScanActivity
import io.github.itshedimisawi.vitro.Glass
import io.github.itshedimisawi.vitro.glassBackdrop
import io.github.itshedimisawi.vitro.glassEffect
import io.github.itshedimisawi.vitro.rememberGlassBackdrop
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainScreen() }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backdrop = rememberGlassBackdrop()
    val pages = ScanSession.pages

    var exportProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var exportingLabel by remember { mutableStateOf("") }

    // 为缺少缩略图的页面生成缩略图
    LaunchedEffect(pages.toList()) {
        pages.filter { it.thumbnailPath == null || !File(it.thumbnailPath).exists() }
            .forEach { page ->
                val path = com.example.docscanner.cv.PageProcessor
                    .generateThumbnail(page, context.cacheDir) ?: return@forEach
                val idx = pages.indexOfFirst { it.id == page.id }
                if (idx >= 0) {
                    pages[idx] = pages[idx].copy(thumbnailPath = path)
                }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(listOf(Color(0xFF3A3A55), Color(0xFF1A1A1E)))
            )
            .glassBackdrop(backdrop)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassEffect(backdrop, Glass.Regular)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "文档扫描",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text("${pages.size} 页", color = Color.White.copy(alpha = 0.7f))
            }

            // 页面网格
            Box(modifier = Modifier.weight(1f)) {
                if (pages.isEmpty()) {
                    Text(
                        "还没有扫描页面\n点击下方按钮开始",
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(pages, key = { it.id }) { page ->
                            PageCell(
                                pageId = page.id,
                                thumbnailPath = page.thumbnailPath,
                                backdrop = backdrop,
                                onClick = {
                                    context.startActivity(
                                        Intent(context, EditActivity::class.java)
                                            .putExtra(EditActivity.EXTRA_PAGE_ID, page.id)
                                    )
                                },
                                onDelete = { ScanSession.remove(page.id) }
                            )
                        }
                    }
                }
            }

            // 底部操作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassEffect(backdrop, Glass.Regular.interactive())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    context.startActivity(Intent(context, CameraScanActivity::class.java))
                }) { Text("扫描", color = Color.White) }

                TextButton(
                    enabled = pages.isNotEmpty() && exportProgress == null,
                    onClick = {
                        val list = pages.toList()
                        scope.launch {
                            exportingLabel = "导出 PDF"
                            exportProgress = 0 to list.size
                            val uri = ExportManager.exportPdf(context, list) { done, total ->
                                exportProgress = done to total
                            }
                            exportProgress = null
                            Toast.makeText(
                                context,
                                if (uri != null) "PDF 已导出到 Downloads/DocScanner"
                                else "导出失败",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                ) { Text("导出 PDF", color = Color.White) }

                TextButton(
                    enabled = pages.isNotEmpty() && exportProgress == null,
                    onClick = {
                        val list = pages.toList()
                        scope.launch {
                            exportingLabel = "导出图片"
                            exportProgress = 0 to list.size
                            val count = ExportManager.exportImages(context, list) { done, total ->
                                exportProgress = done to total
                            }
                            exportProgress = null
                            Toast.makeText(
                                context,
                                "已导出 $count 张图片到 Pictures/DocScanner",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                ) { Text("导出图片", color = Color.White) }

                TextButton(
                    enabled = pages.isNotEmpty() && exportProgress == null,
                    onClick = {
                        ScanSession.clear()
                        context.cacheDir.resolve("thumb").deleteRecursively()
                    }
                ) { Text("清空", color = Color.White.copy(alpha = 0.8f)) }
            }
        }

        // 导出进度
        exportProgress?.let { (done, total) ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text(exportingLabel) },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("$done / $total")
                    }
                }
            )
        }
    }
}

@Composable
private fun PageCell(
    pageId: String,
    thumbnailPath: String?,
    backdrop: io.github.itshedimisawi.vitro.GlassBackdrop,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(0.72f)
            .glassEffect(backdrop, Glass.Clear.dimmed(), shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        if (thumbnailPath != null) {
            AsyncImage(
                model = File(thumbnailPath),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(24.dp)
            )
        }

        // 删除角标
        Text(
            "×",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(22.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(11.dp))
                .clickable(onClick = onDelete)
                .wrapContentSize(Alignment.Center)
        )
    }
}
