package io.github.bileizhen.pan123x.data.settings

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Deletes only completed preview cache files. Active .part files and all transfer data survive. */
class PreviewCacheCleaner(private val cacheRoot: File) {
    suspend fun clear(): Long = withContext(Dispatchers.IO) {
        val preview = File(cacheRoot, "previews")
        if (!preview.exists()) return@withContext 0L
        if (Files.isSymbolicLink(preview.toPath()) || preview.canonicalFile.parentFile != cacheRoot.canonicalFile) throw IOException("预览缓存目录不可用")
        var freed = 0L
        val pending = ArrayDeque<File>().apply { add(preview) }
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val files = pending.removeFirst().listFiles() ?: throw IOException("无法读取预览缓存")
            files.forEach { file ->
                if (Files.isSymbolicLink(file.toPath())) return@forEach
                when {
                    file.isDirectory -> pending.add(file)
                    file.isFile && !file.name.endsWith(".part") -> {
                        val bytes = file.length()
                        if (file.delete()) freed += bytes else throw IOException("部分预览缓存无法清理，请稍后重试")
                    }
                }
            }
        }
        freed
    }
}
