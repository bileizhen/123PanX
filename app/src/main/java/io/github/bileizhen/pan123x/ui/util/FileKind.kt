package io.github.bileizhen.pan123x.ui.util

/**
 * 文件的展示分类：图标、文案与后续预览入口共用。
 *
 * 后缀表逐项对照协议真源 `.reference/123pan/src/app/preview/preview_manager.py`
 * （图片 / 视频 / 音频 / 文本 / PDF 五类）；参考源未收录的后缀（含压缩包）一律
 * [OTHER]，不自行扩表，避免客户端分类与协议预览能力不一致。
 */
enum class FileKind(val label: String, val symbol: String) {
    FOLDER("文件夹", "▱"),
    IMAGE("图片", "▧"),
    VIDEO("视频", "▷"),
    AUDIO("音频", "♫"),
    PDF("PDF", "PDF"),
    TEXT("文本", "TXT"),
    OTHER("文件", "◇"),
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "ico", "tiff", "tif",
)

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "m4v", "3gp",
)

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "wav", "flac", "ogg", "aac", "wma", "m4a", "opus", "ape",
)

private val TEXT_EXTENSIONS = setOf(
    "txt", "log", "py", "json", "xml", "md", "csv", "ini", "cfg",
    "yml", "yaml", "toml", "sh", "bat", "ps1", "sql", "html", "css",
    "js", "ts", "c", "cpp", "h", "hpp", "java", "kt", "rs", "go",
    "rb", "php", "lua", "r", "swift", "scala", "conf", "env",
)

/** 按是否文件夹与文件名后缀取展示分类；无后缀 / 未知后缀归 [FileKind.OTHER]。 */
fun fileKindOf(isFolder: Boolean, fileName: String): FileKind {
    if (isFolder) return FileKind.FOLDER
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when {
        extension.isEmpty() -> FileKind.OTHER
        extension in IMAGE_EXTENSIONS -> FileKind.IMAGE
        extension in VIDEO_EXTENSIONS -> FileKind.VIDEO
        extension in AUDIO_EXTENSIONS -> FileKind.AUDIO
        extension in TEXT_EXTENSIONS -> FileKind.TEXT
        extension == "pdf" -> FileKind.PDF
        else -> FileKind.OTHER
    }
}
