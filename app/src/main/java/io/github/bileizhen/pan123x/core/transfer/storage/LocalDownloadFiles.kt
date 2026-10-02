package io.github.bileizhen.pan123x.core.transfer.storage

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import io.github.bileizhen.pan123x.core.database.TransferDirection
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.database.TransferTaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Opens the completed local download, including legacy internal file:// records. */
class LocalDownloadFiles(private val context: Context) {
    suspend fun prepare(task: TransferTaskEntity, share: Boolean): Intent = withContext(Dispatchers.IO) {
        require(task.direction == TransferDirection.DOWNLOAD && task.state == TransferState.COMPLETED) {
            "下载完成后才能打开或分享文件"
        }
        require(task.targetUri.isNotBlank()) { "文件不存在或已移除，请重新下载" }
        val original = Uri.parse(task.targetUri)
        val uri = when (original.scheme) {
            "content" -> original
            "file" -> {
                val root = File(context.filesDir, "downloads").canonicalFile
                val file = File(requireNotNull(original.path)).canonicalFile
                require(file.toPath().startsWith(root.toPath()) && file != root) { "无法读取此下载文件" }
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
            else -> throw IllegalArgumentException("无法读取此下载文件")
        }
        checkNotNull(context.contentResolver.openFileDescriptor(uri, "r")) {
            "文件不存在或已移除，请重新下载"
        }.use { }
        val extension = task.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = context.contentResolver.getType(uri)
            ?.takeUnless { it == "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        intent.clipData = ClipData.newRawUri(task.fileName, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent
    }

    suspend fun launch(task: TransferTaskEntity, share: Boolean): String? = try {
        val intent = prepare(task, share)
        withContext(Dispatchers.Main) {
            val chooser = Intent.createChooser(intent, if (share) "分享文件" else "打开文件")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            chooser.clipData = intent.clipData
            if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
        null
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        "无法读取下载文件，请重新授予下载目录权限"
    } catch (_: java.io.FileNotFoundException) {
        "文件不存在或已移除，请重新下载"
    } catch (_: android.content.ActivityNotFoundException) {
        "没有可打开此文件的应用"
    } catch (failure: IllegalArgumentException) {
        failure.message ?: "无法读取此下载文件"
    } catch (_: Exception) {
        "无法读取此下载文件，请检查文件和下载目录"
    }
}
