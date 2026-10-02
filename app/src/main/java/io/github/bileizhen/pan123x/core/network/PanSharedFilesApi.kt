package io.github.bileizhen.pan123x.core.network

import io.github.bileizhen.pan123x.core.transfer.download.DownloadSource

/** Public share contents are separate from the signed-in user's share management list. */
interface PanSharedFilesApi {
    suspend fun sharedFiles(key: String, password: String, parentId: Long = 0, page: Int = 1, next: String = "0"): ApiResult<FileListDto>
    suspend fun saveSharedFiles(key: String, password: String, files: List<FileItemDto>, targetId: Long): ApiResult<SharedSaveTask>
    suspend fun sharedSaveStatus(taskId: String): ApiResult<SharedSaveTask>
    suspend fun sharedDownloadLink(source: DownloadSource): ApiResult<DownloadLinkDto>
}

data class SharedSaveTask(val taskId: String, val complete: Boolean, val failed: Boolean = false)
