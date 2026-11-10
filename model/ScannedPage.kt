package com.example.docscanner.model

data class ScannedPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** 原始拍照文件，永不覆盖 */
    val rawPath: String,
    /** 当前工作图（裁切/旋转物化后的文件），初始等于 rawPath */
    val workingPath: String = rawPath,
    /** 是否已尝试过自动裁剪，防止每次进编辑页重复检测 */
    val autoCropAttempted: Boolean = false,
    /** 顺时针 90° 步进，0..3 */
    val quarterTurns: Int = 0,
    val filterMode: FilterMode = FilterMode.ENHANCE,
    /** 缩略图缓存文件路径，变更时置空触发重新生成 */
    val thumbnailPath: String? = null
)

enum class FilterMode { ORIGINAL, ENHANCE, BINARY }
