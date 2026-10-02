// Release validation adapted from bileizhen/LeiFetch AppUpdates.kt, GPL-3.0-only.
package io.github.bileizhen.pan123x.data.settings

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppRelease(val version: String, val notes: String, val url: String)
sealed interface UpdateResult {
    data class Available(val release: AppRelease) : UpdateResult
    data object Current : UpdateResult
    data object Unpublished : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

object ReleaseParser {
    const val REPOSITORY = "bileizhen/123PanX"
    private val json = Json { ignoreUnknownKeys = true }
    private val version = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)\\.(0|[1-9][0-9]*))?")

    fun parseRelease(body: String, installed: String): UpdateResult {
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root.string("tag_name")
        val candidate = parseVersion(tag) ?: return UpdateResult.Failed("更新版本格式无效")
        // Local milestone/debug builds use their base version for comparison.
        val current = parseVersion(installed.replace(Regex("-m[0-9]+-debug$"), ""))
            ?: return UpdateResult.Failed("无法比较当前应用版本")
        if (root["draft"]?.jsonPrimitive?.booleanOrNull == true || root["prerelease"]?.jsonPrimitive?.booleanOrNull == true || candidate[3] != 3L)
            return UpdateResult.Failed("未找到正式发布版本")
        val page = "https://github.com/$REPOSITORY/releases/tag/$tag"
        if (root.string("html_url") != page) return UpdateResult.Failed("更新来源不匹配")
        val assets = root["assets"] as? JsonArray ?: return UpdateResult.Failed("该版本没有 Android 安装包")
        val hasApk = assets.any { element ->
            val asset = element as? JsonObject ?: return@any false
            val download = asset.string("browser_download_url").toHttpUrlOrNullSafe()
            asset.string("name").endsWith(".apk", true) && download?.scheme == "https" && download.host == "github.com" &&
                download.encodedPath.startsWith("/$REPOSITORY/releases/download/$tag/") && download.username.isBlank() && download.password.isBlank()
        }
        if (!hasApk) return UpdateResult.Failed("该版本没有可信的 Android 安装包")
        val comparison = candidate.zip(current).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: 0
        return if (comparison > 0) UpdateResult.Available(AppRelease(tag.removePrefix("v"), root.string("body").take(12_000), page)) else UpdateResult.Current
    }

    private fun String.toHttpUrlOrNullSafe(): HttpUrl? = try { toHttpUrl() } catch (_: IllegalArgumentException) { null }
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun parseVersion(value: String): List<Long>? {
        val match = version.matchEntire(value) ?: return null
        val numbers = match.groupValues.drop(1).take(3).map { it.toLongOrNull() ?: return null }
        return numbers + when (match.groupValues[4]) { "alpha" -> 0L; "beta" -> 1L; "rc" -> 2L; else -> 3L } + (match.groupValues[5].toLongOrNull() ?: 0)
    }
}

class UpdateRepository(private val client: OkHttpClient, private val installed: String,
    private val endpoint: HttpUrl = "https://api.github.com/repos/${ReleaseParser.REPOSITORY}/releases/latest".toHttpUrl()) {
    @OptIn(InternalCoroutinesApi::class)
    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(endpoint).header("Accept", "application/vnd.github+json").header("User-Agent", "123PanX/$installed").build())
        val cancel = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                when (response.code) {
                    404 -> UpdateResult.Unpublished
                    403, 429 -> UpdateResult.Failed("检查更新受到限流，请稍后重试")
                    200 -> {
                        val input = response.body?.byteStream() ?: throw IOException("empty")
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        input.use { while (output.size() <= MAX_BYTES) {
                            currentCoroutineContext().ensureActive()
                            val read = it.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        } }
                        if (output.size() > MAX_BYTES) UpdateResult.Failed("更新响应过大")
                        else try { ReleaseParser.parseRelease(output.toString("UTF-8"), installed) }
                        catch (_: IllegalArgumentException) { UpdateResult.Failed("无法解析更新信息") }
                    }
                    else -> UpdateResult.Failed("检查更新失败（HTTP ${response.code}）")
                }
            }
        } catch (_: IOException) { currentCoroutineContext().ensureActive(); UpdateResult.Failed("无法连接更新服务器，请检查网络或代理") }
        finally { cancel.dispose() }
    }
    private companion object { const val MAX_BYTES = 256 * 1024 }
}
