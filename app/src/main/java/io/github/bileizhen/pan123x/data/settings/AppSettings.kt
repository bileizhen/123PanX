package io.github.bileizhen.pan123x.data.settings

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

enum class AppLanguage(val label: String) { CHINESE("简体中文"), ENGLISH("English"), SYSTEM("跟随系统") }

/** Appearance settings contain no Android or Compose state. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val monet: Boolean = true,
    val uiScale: Float = 1f,
    val blur: Boolean = true,
    val floatingBar: Boolean = true,
    val liquidGlass: Boolean = true,
    val predictiveBack: Boolean = true,
    val downloadConnections: Int = 4,
    val downloadTree: String = "",
    val recognizeShareClipboard: Boolean = true,
    val autoCheckUpdates: Boolean = true,
    val askDownloadLocation: Boolean = false,
    val multiThreadDownload: Boolean = true,
    val uploadThreads: Int = 1,
    val maxConcurrentDownloads: Int = 3,
    val maxConcurrentUploads: Int = 3,
    val downloadSpeedLimit: Long = 0,
    val uploadSpeedLimit: Long = 0,
    val clientSimulation: Boolean = true,
    val errorBackoffRetry: Boolean = true,
    val logLevel: String = "INFO",
    val language: AppLanguage = AppLanguage.CHINESE,
    // M6 文本预览直接拉取的最大字节数（大文本避免一次性全量加载）。
    val maxTextPreviewBytes: Long = DEFAULT_MAX_TEXT_PREVIEW_BYTES,
) {
    fun normalized(): AppSettings = copy(
        downloadConnections = downloadConnections.takeIf { it in CONNECTION_OPTIONS } ?: 4,
        uploadThreads = uploadThreads.coerceIn(1, 4),
        maxConcurrentDownloads = maxConcurrentDownloads.coerceIn(1, 32),
        maxConcurrentUploads = maxConcurrentUploads.coerceIn(1, 32),
        downloadSpeedLimit = downloadSpeedLimit.coerceIn(0, MAX_SPEED_LIMIT),
        uploadSpeedLimit = uploadSpeedLimit.coerceIn(0, MAX_SPEED_LIMIT),
        logLevel = logLevel.takeIf { it in LOG_LEVEL_OPTIONS } ?: "INFO",
        uiScale = if (uiScale.isFinite()) uiScale.coerceIn(MIN_SCALE, MAX_SCALE) else 1f,
        // 档位外的值（手改数据文件等）回吸到默认档，UI 的档位选择器不会出现"无选中项"。
        maxTextPreviewBytes = if (maxTextPreviewBytes in TEXT_PREVIEW_OPTIONS) maxTextPreviewBytes
        else DEFAULT_MAX_TEXT_PREVIEW_BYTES,
    )

    companion object {
        val CONNECTION_OPTIONS = listOf(1, 2, 4, 8, 16)
        val UPLOAD_THREAD_OPTIONS = (1..4).toList()
        val CONCURRENT_OPTIONS = listOf(1, 2, 3, 4, 8, 16, 32)
        val SPEED_OPTIONS = listOf(0L, 128L * 1024, 512L * 1024, 1024L * 1024, 5L * 1024 * 1024, 10L * 1024 * 1024, 20L * 1024 * 1024)
        val LOG_LEVEL_OPTIONS = listOf("DEBUG", "INFO", "WARNING", "ERROR")
        const val MAX_SPEED_LIMIT = 1024L * 1024 * 1024
        const val MIN_SCALE = 0.8f
        const val MAX_SCALE = 1.2f
        const val DEFAULT_MAX_TEXT_PREVIEW_BYTES = 1L * 1024 * 1024

        /** 设置页可选档位。 */
        val TEXT_PREVIEW_OPTIONS: List<Long> = listOf(
            256L * 1024, 1L * 1024 * 1024, 4L * 1024 * 1024, 16L * 1024 * 1024,
        )
    }
}
