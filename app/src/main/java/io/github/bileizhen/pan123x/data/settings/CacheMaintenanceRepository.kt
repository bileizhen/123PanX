package io.github.bileizhen.pan123x.data.settings

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.CacheMaintenanceDao
import io.github.bileizhen.pan123x.data.file.FileRepository

class CacheMaintenanceRepository(
    private val dao: CacheMaintenanceDao,
    private val manager: AccountManager,
    private val files: FileRepository,
    private val clearPreviews: suspend () -> Long,
) {
    suspend fun clearCache(): Long = clearPreviews()
    suspend fun refreshFileLists() {
        dao.invalidate(accountId())
        files.notifyCacheInvalidated()
    }
    suspend fun resetFileLists() {
        dao.clearFileMetadata(accountId())
        files.notifyCacheInvalidated()
    }
    private fun accountId(): String = (manager.state.value as? SessionState.Ready)?.accountId
        ?: throw IllegalStateException("请先登录账户")
}
