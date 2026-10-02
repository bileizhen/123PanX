package io.github.bileizhen.pan123x.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UploadPartDao 行为测试（Room in-memory）。
 *
 * 为什么放在 androidTest 而非 test：Room 的 DAO 实现依赖 Android SQLite，纯 JVM 单测无法构造；
 * 与 AppDatabaseTest / MigrationTest 一致走 instrumentation。验证点：整体替换语义、单分片幂等标记、
 * 清空、排序观察、复合外键级联、多账户隔离。
 */
@RunWith(AndroidJUnit4::class)
class UploadPartDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() { database.close() }

    @Test
    fun replaceForSwapsWholePlanInOneShot() = runBlocking {
        seed()

        database.uploadPartDao().replaceFor(
            ACCOUNT,
            TASK,
            listOf(UploadPartEntity(ACCOUNT, TASK, 1, 5), UploadPartEntity(ACCOUNT, TASK, 2, 3)),
        )
        assertEquals(listOf(1, 2), database.uploadPartDao().observe(ACCOUNT, TASK).first().map { it.partNumber })

        // 计划变化：整体替换后只保留新计划
        database.uploadPartDao().replaceFor(ACCOUNT, TASK, listOf(UploadPartEntity(ACCOUNT, TASK, 1, 8)))
        val parts = database.uploadPartDao().observe(ACCOUNT, TASK).first()
        assertEquals(listOf(1), parts.map { it.partNumber })
        assertEquals(8, parts.single().size)
    }

    @Test
    fun markUploadedIsIdempotentPerPartNumber() = runBlocking {
        seed()
        database.uploadPartDao().replaceFor(ACCOUNT, TASK, listOf(UploadPartEntity(ACCOUNT, TASK, 1, 5)))

        database.uploadPartDao().markUploaded(UploadPartEntity(ACCOUNT, TASK, 2, 3))
        database.uploadPartDao().markUploaded(UploadPartEntity(ACCOUNT, TASK, 2, 3))

        val parts = database.uploadPartDao().observe(ACCOUNT, TASK).first()
        assertEquals(listOf(1, 2), parts.map { it.partNumber })
    }

    @Test
    fun clearRemovesOnlyRequestedTask() = runBlocking {
        seed()
        database.transferTaskDao().upsert(task(ACCOUNT, OTHER_TASK))
        database.uploadPartDao().replaceFor(ACCOUNT, TASK, listOf(UploadPartEntity(ACCOUNT, TASK, 1, 5)))
        database.uploadPartDao().replaceFor(ACCOUNT, OTHER_TASK, listOf(UploadPartEntity(ACCOUNT, OTHER_TASK, 1, 5)))

        database.uploadPartDao().clear(ACCOUNT, TASK)

        assertTrue(database.uploadPartDao().observe(ACCOUNT, TASK).first().isEmpty())
        assertEquals(1, database.uploadPartDao().observe(ACCOUNT, OTHER_TASK).first().size)
    }

    @Test
    fun uploadPartsCascadeWhenTaskDeleted() = runBlocking {
        seed()
        database.uploadPartDao().replaceFor(ACCOUNT, TASK, listOf(UploadPartEntity(ACCOUNT, TASK, 1, 5)))

        database.transferTaskDao().delete(ACCOUNT, TASK)

        assertTrue(
            "删除任务行必须级联清理 upload_parts（复合外键 CASCADE）",
            database.uploadPartDao().observe(ACCOUNT, TASK).first().isEmpty(),
        )
    }

    @Test
    fun identicalTaskIdsRemainIsolatedPerAccount() = runBlocking {
        database.accountDao().upsert(AccountEntity(ACCOUNT, "Account A"))
        database.accountDao().upsert(AccountEntity("acc-b", "Account B"))
        database.transferTaskDao().upsert(task(ACCOUNT, TASK))
        database.transferTaskDao().upsert(task("acc-b", TASK))

        database.uploadPartDao().replaceFor(ACCOUNT, TASK, listOf(UploadPartEntity(ACCOUNT, TASK, 1, 5)))
        database.uploadPartDao().replaceFor("acc-b", TASK, listOf(UploadPartEntity("acc-b", TASK, 1, 9)))

        assertEquals(5, database.uploadPartDao().observe(ACCOUNT, TASK).first().single().size)
        assertEquals(9, database.uploadPartDao().observe("acc-b", TASK).first().single().size)
    }

    private suspend fun seed() {
        database.accountDao().upsert(AccountEntity(ACCOUNT, "Account A"))
        database.transferTaskDao().upsert(task(ACCOUNT, TASK))
    }

    private fun task(accountId: String, taskId: String) = TransferTaskEntity(
        accountId = accountId,
        taskId = taskId,
        fileName = "$taskId.bin",
        direction = TransferDirection.UPLOAD,
        size = 8,
    )

    private companion object {
        const val ACCOUNT = "acc-a"
        const val TASK = "task-1"
        const val OTHER_TASK = "task-2"
    }
}
