package io.github.bileizhen.pan123x.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() { database.close() }

    @Test fun resettingOneAccountsFileMetadataKeepsAccountsOtherFilesAndTransferCheckpoints() = runBlocking {
        seedAccounts()
        database.cloudFileDao().upsert(listOf(file("a", "cached-a.bin"), file("b", "cached-b.bin")))
        database.directoryStateDao().upsert(DirectoryStateEntity("a", 0, 1, true, 123L))
        database.directoryStateDao().upsert(DirectoryStateEntity("b", 0, 1, true, 456L))
        database.transferTaskDao().upsert(task("a", "keep-task.bin").copy(state = TransferState.PAUSED, downloadedBytes = 2048L))
        database.cacheMaintenanceDao().invalidate("a")
        assertEquals(0L, database.directoryStateDao().get("a", 0)!!.updatedAt)
        assertEquals(456L, database.directoryStateDao().get("b", 0)!!.updatedAt)
        assertEquals("cached-a.bin", database.cloudFileDao().get("a", 1)!!.fileName)
        database.cacheMaintenanceDao().clearFileMetadata("a")
        assertNull(database.cloudFileDao().get("a", 1)); assertNull(database.directoryStateDao().get("a", 0))
        assertEquals("cached-b.bin", database.cloudFileDao().get("b", 1)!!.fileName)
        assertEquals(2, database.accountDao().observeAccounts().first().size)
        assertEquals(2048L, database.transferTaskDao().get("a", "same-task")!!.downloadedBytes)
        assertEquals(TransferState.PAUSED, database.transferTaskDao().get("a", "same-task")!!.state)
    }

    @Test
    fun identicalFileAndTaskIdsRemainIsolatedPerAccount() = runBlocking {
        seedAccounts()
        database.cloudFileDao().upsert(listOf(file("a", "a.txt"), file("b", "b.txt")))
        database.directoryStateDao().upsert(DirectoryStateEntity("a", 0, total = 1, allLoaded = true, updatedAt = 1L))
        database.transferTaskDao().upsert(task("a", "first.txt"))
        database.transferTaskDao().upsert(task("b", "second.txt"))
        assertEquals("a.txt", database.cloudFileDao().get("a", 1)?.fileName)
        assertEquals("b.txt", database.cloudFileDao().get("b", 1)?.fileName)
        assertEquals("first.txt", database.transferTaskDao().get("a", "same-task")?.fileName)
        assertEquals("second.txt", database.transferTaskDao().get("b", "same-task")?.fileName)
        assertEquals(1, database.cloudFileDao().observeDirectory("a", 0).first().size)
        database.accountDao().delete("a")
        assertNull(database.cloudFileDao().get("a", 1))
        // directory_states 同样挂在 accountId 外键上，账户删除时级联清理
        assertNull(database.directoryStateDao().get("a", 0))
        assertNull(database.transferTaskDao().get("a", "same-task"))
        assertEquals("b.txt", database.cloudFileDao().get("b", 1)?.fileName)
        assertEquals("second.txt", database.transferTaskDao().get("b", "same-task")?.fileName)
    }

    @Test
    fun invalidDirectorySnapshotCannotDeleteExistingCache() = runBlocking {
        seedAccounts()
        database.cloudFileDao().upsert(listOf(file("a", "cached.txt")))
        var rejected = false
        try {
            database.cloudFileDao().replaceDirectory("a", 0, listOf(file("b", "wrong-account.txt")))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals("cached.txt", database.cloudFileDao().get("a", 1)?.fileName)
    }

    @Test
    fun successfulDirectorySnapshotUpdatesOnlyRequestedAccount() = runBlocking {
        seedAccounts()
        database.cloudFileDao().upsert(listOf(file("a", "old.txt"), file("b", "other.txt")))
        database.cloudFileDao().replaceDirectory("a", 0, listOf(file("a", "new.txt")))
        assertEquals("new.txt", database.cloudFileDao().get("a", 1)?.fileName)
        assertEquals("other.txt", database.cloudFileDao().get("b", 1)?.fileName)
    }

    @Test
    fun replaceDirectoryWithStateWritesFilesAndStateTogether() = runBlocking {
        seedAccounts()
        database.cloudFileDao().upsert(listOf(file("a", "stale.txt")))
        database.directoryStateDao().upsert(DirectoryStateEntity("a", 0, total = 9, allLoaded = false, updatedAt = 1L))

        database.cloudFileDao().replaceDirectoryWithState(
            accountId = "a",
            parentFileId = 0,
            files = listOf(file("a", "fresh.txt")),
            total = 1,
            allLoaded = true,
            updatedAt = 42L,
        )

        assertEquals("fresh.txt", database.cloudFileDao().get("a", 1)?.fileName)
        assertEquals(
            DirectoryStateEntity("a", 0, total = 1, allLoaded = true, updatedAt = 42L),
            database.directoryStateDao().get("a", 0),
        )
        // 观察流能看到新状态：total / allLoaded 与文件快照来自同一次替换
        val observed = database.directoryStateDao().observe("a", 0).first()
        assertEquals(true, observed?.allLoaded)
    }

    @Test
    fun replaceDirectoryWithStateRejectsMismatchedAccountAndWritesNothing() = runBlocking {
        seedAccounts()
        database.cloudFileDao().upsert(listOf(file("a", "cached.txt")))

        var rejected = false
        try {
            database.cloudFileDao().replaceDirectoryWithState(
                accountId = "a",
                parentFileId = 0,
                files = listOf(file("b", "wrong-account.txt")),
                total = 1,
                allLoaded = true,
                updatedAt = 1L,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals("cached.txt", database.cloudFileDao().get("a", 1)?.fileName)
        assertNull(database.directoryStateDao().get("a", 0))
    }

    private suspend fun seedAccounts() {
        database.accountDao().upsert(AccountEntity("a", "Account A"))
        database.accountDao().upsert(AccountEntity("b", "Account B"))
    }

    private fun file(accountId: String, name: String) = CloudFileEntity(accountId, 1, 0, name, false)
    private fun task(accountId: String, name: String) = TransferTaskEntity(
        accountId, "same-task", fileName = name, direction = TransferDirection.DOWNLOAD,
    )
}
