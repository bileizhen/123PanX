package io.github.bileizhen.pan123x.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 迁移链验证 v1 → v2 → v3 → v4 → v5（禁止破坏性迁移）。
 *
 * 注意：用例构造的是**旧版本**库，而 Room 会打开到**当前**版本，所以每个用例都必须注册
 * **完整迁移路径**（如 v1 用例注册 `MIGRATION_1_2 + MIGRATION_2_3 + MIGRATION_3_4 + MIGRATION_4_5`）。M4 把库
 * 升到 v3 时漏了这一点，导致 v1 用例报 `A migration from 1 to 3 was required but not found`——
 * 真机首次运行 instrumentation 才暴露（此前无设备，androidTest 从未跑过）。
 *
 * 实现说明（偏离 MigrationTestHelper 的原因）：本仓库 build.gradle.kts 未把 schemas 目录
 * 配置进 androidTest assets，MigrationTestHelper 的 assets 装载路径无法取到 schema JSON。
 * 因此这里按 app/schemas/.../<n>.json 的 createSql 逐字手工构建旧库（含该版本的 identity
 * hash 与 user_version），再用 Room 正式打开：RoomOpenHelper 发现 identity hash 与当前版本
 * 期望不一致时，会用编译期生成的期望 schema 校验迁移后的全部表结构（TableInfo 列 / 索引 /
 * 外键），校验失败即抛 "Migration didn't properly handle"——与 MigrationTestHelper 的校验机制相同。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private lateinit var context: Context
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate1To2PreservesLegacyRowsAndZeroesStringDates() {
        createLegacyVersion1Database()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
        try {
            // 打开即触发 1->2 迁移与 Room 的 schema 校验；迁移 SQL 有任何偏差都会在这里抛异常
            database.openHelper.writableDatabase
            runBlocking {
                val account = database.accountDao().get(LEGACY_ACCOUNT)!!
                assertEquals(false, account.hasCloudInfo)
                assertEquals("", account.maskedPassport)
                assertEquals(0L, account.fileCount)
                database.accountDao().upsert(account.copy(hasCloudInfo = true, maskedPassport = "138****8000", fileCount = 22, professionalTotalBytes = 50_000, vip = true))
                assertEquals(22L, database.accountDao().get(LEGACY_ACCOUNT)?.fileCount)
                assertEquals(50_000L, database.accountDao().get(LEGACY_ACCOUNT)?.professionalTotalBytes)
                assertEquals(true, database.accountDao().get(LEGACY_ACCOUNT)?.vip)
                val legacy = database.cloudFileDao().get(LEGACY_ACCOUNT, 11L)
                assertNotNull(legacy)
                assertEquals("legacy.txt", legacy?.fileName)
                assertEquals(5L, legacy?.size)
                assertEquals("etag-11", legacy?.etag)
                // v1 的字符串日期无法可靠解析，迁移时统一置 0（见 MIGRATION_1_2 注释）
                assertEquals(0L, legacy?.createAt)
                assertEquals(0L, legacy?.updateAt)

                // directory_states 新表可写可读可观察
                database.directoryStateDao().upsert(
                    DirectoryStateEntity(LEGACY_ACCOUNT, dirId = 0, total = 7, allLoaded = true, updatedAt = 123L),
                )
                assertEquals(7, database.directoryStateDao().get(LEGACY_ACCOUNT, 0)?.total)
                assertEquals(true, database.directoryStateDao().observe(LEGACY_ACCOUNT, 0).first()?.allLoaded)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migratedDirectoryStatesCascadeOnAccountDelete() {
        createLegacyVersion1Database()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
        try {
            database.openHelper.writableDatabase
            runBlocking {
                database.directoryStateDao().upsert(
                    DirectoryStateEntity(LEGACY_ACCOUNT, dirId = 0, total = 1, allLoaded = true, updatedAt = 1L),
                )
                assertNotNull(database.directoryStateDao().get(LEGACY_ACCOUNT, 0))

                database.accountDao().delete(LEGACY_ACCOUNT)

                // accountId 外键级联：文件与目录状态一起清理（多账户隔离）
                assertNull(database.cloudFileDao().get(LEGACY_ACCOUNT, 11L))
                assertNull(database.directoryStateDao().get(LEGACY_ACCOUNT, 0))
            }
        } finally {
            database.close()
        }
    }

    /**
     * v2 -> v3 迁移（M4）：`transfer_tasks` 追加 `destinationTree` / `segments` 两列，新增
     * `download_segments` 表，且既有传输任务行必须完整保留（禁止破坏性迁移）。
     */
    @Test
    fun migrate2To3AddsSegmentTableAndColumnsPreservingTasks() {
        createVersion2Database()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
        try {
            database.openHelper.writableDatabase
            runBlocking {
                // 旧任务行保留，新列取迁移时的 DEFAULT
                val task = database.transferTaskDao().get(LEGACY_ACCOUNT, LEGACY_TASK)
                assertNotNull(task)
                assertEquals("legacy.bin", task?.fileName)
                assertEquals(TransferState.PAUSED, task?.state)
                assertEquals(4096L, task?.downloadedBytes)
                assertEquals("", task?.destinationTree)
                assertEquals(0, task?.segments)

                // download_segments 新表可写可读，且外键指向 transfer_tasks
                database.downloadSegmentDao().replaceFor(
                    LEGACY_ACCOUNT,
                    LEGACY_TASK,
                    listOf(
                        DownloadSegmentEntity(LEGACY_ACCOUNT, LEGACY_TASK, 0, 0, 2048, 2048),
                        DownloadSegmentEntity(LEGACY_ACCOUNT, LEGACY_TASK, 1, 2048, 4096, 0),
                    ),
                )
                val segments = database.downloadSegmentDao().observe(LEGACY_ACCOUNT, LEGACY_TASK).first()
                assertEquals(listOf(0, 1), segments.map { it.segmentIndex })
                assertEquals(listOf(2048L, 0L), segments.map { it.downloaded })

                // 迁移后新列可正常参与更新（updateProgress 会写 state）
                database.transferTaskDao().updateProgress(
                    LEGACY_ACCOUNT, LEGACY_TASK, 4096, TransferState.COMPLETED.name, 9L,
                )
                assertEquals(TransferState.COMPLETED, database.transferTaskDao().get(LEGACY_ACCOUNT, LEGACY_TASK)?.state)
            }
        } finally {
            database.close()
        }
    }

    /**
     * v3 -> v4 迁移（M5）：`transfer_tasks` 追加上传会话 8 列（默认值为空串 / 0），新增
     * `upload_parts` 表，既有任务行必须完整保留（禁止破坏性迁移）。
     */
    @Test
    fun migrate3To4AddsUploadSessionColumnsAndPartsTablePreservingTasks() {
        createVersion3Database()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
        try {
            database.openHelper.writableDatabase
            runBlocking {
                // 旧任务行保留，新列取迁移时的 DEFAULT
                val task = database.transferTaskDao().get(LEGACY_ACCOUNT, LEGACY_TASK)
                assertNotNull(task)
                assertEquals("legacy.bin", task?.fileName)
                assertEquals(TransferState.PAUSED, task?.state)
                assertEquals("", task?.s3KeyFlag)
                assertEquals(0L, task?.parentFileId)
                assertEquals("", task?.bucket)
                assertEquals("", task?.storageNode)
                assertEquals("", task?.uploadKey)
                assertEquals("", task?.uploadId)
                assertEquals(0L, task?.sourceMtime)
                assertEquals(0L, task?.blockSize)

                // upload_parts 新表可写可读，且外键指向 transfer_tasks（复合主键含 partNumber）
                database.uploadPartDao().replaceFor(
                    LEGACY_ACCOUNT,
                    LEGACY_TASK,
                    listOf(
                        UploadPartEntity(LEGACY_ACCOUNT, LEGACY_TASK, 1, 5_242_880),
                        UploadPartEntity(LEGACY_ACCOUNT, LEGACY_TASK, 2, 1_048_576),
                    ),
                )
                val parts = database.uploadPartDao().observe(LEGACY_ACCOUNT, LEGACY_TASK).first()
                assertEquals(listOf(1, 2), parts.map { it.partNumber })
                assertEquals(listOf(5_242_880, 1_048_576), parts.map { it.size })

                // accountId 外键级联：删账户连带清理任务与分片
                database.accountDao().delete(LEGACY_ACCOUNT)
                assertNull(database.transferTaskDao().get(LEGACY_ACCOUNT, LEGACY_TASK))
            }
        } finally {
            database.close()
        }
    }

    /** 按 1.json 的 createSql 逐字重建 v1 结构，并写入一行字符串日期的旧格式数据。 */
    private fun createLegacyVersion1Database() {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `accounts` (`accountId` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                    "`uid` TEXT NOT NULL, `usedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `avatarUri` TEXT, " +
                    "PRIMARY KEY(`accountId`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `cloud_files` (`accountId` TEXT NOT NULL, `fileId` INTEGER NOT NULL, " +
                    "`parentFileId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `isFolder` INTEGER NOT NULL, " +
                    "`size` INTEGER NOT NULL, `etag` TEXT NOT NULL, `s3KeyFlag` TEXT NOT NULL, " +
                    "`createAt` TEXT NOT NULL, `updateAt` TEXT NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `fileId`), " +
                    "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`accountId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `transfer_tasks` (`accountId` TEXT NOT NULL, `taskId` TEXT NOT NULL, " +
                    "`fileId` INTEGER, `fileName` TEXT NOT NULL, `direction` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                    "`size` INTEGER NOT NULL, `etag` TEXT NOT NULL, `targetUri` TEXT NOT NULL, " +
                    "`downloadedBytes` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `updateTime` INTEGER NOT NULL, " +
                    "`error` TEXT, PRIMARY KEY(`accountId`, `taskId`), " +
                    "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`accountId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_cloud_files_accountId_parentFileId` ON `cloud_files` (`accountId`, `parentFileId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_transfer_tasks_accountId_state` ON `transfer_tasks` (`accountId`, `state`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            )
            // v1 的 identity hash（来自 app/schemas/.../1.json setupQueries）
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'bd7c6abcbbfa2fe0f1ba675f2e44e472')",
            )
            db.execSQL(
                "INSERT INTO `accounts` (`accountId`, `displayName`, `uid`, `usedBytes`, `totalBytes`) " +
                    "VALUES ('$LEGACY_ACCOUNT', '旧账户', '1', 0, 0)",
            )
            // 旧格式：createAt / updateAt 是字符串日期
            db.execSQL(
                "INSERT INTO `cloud_files` (`accountId`, `fileId`, `parentFileId`, `fileName`, `isFolder`, `size`, `etag`, `s3KeyFlag`, `createAt`, `updateAt`) " +
                    "VALUES ('$LEGACY_ACCOUNT', 11, 0, 'legacy.txt', 0, 5, 'etag-11', 'flag-11', '2024-01-01T08:00:00Z', '2024-01-02T08:00:00Z')",
            )
            db.version = 1
        } finally {
            db.close()
        }
    }

    /**
     * 按 2.json 的 createSql 逐字重建 v2 结构（含 v2 identity hash 与 user_version=2），
     * 并写入一行 PAUSED 状态的传输任务，用于验证迁移不会丢数据。
     */
    private fun createVersion2Database() {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `accounts` (`accountId` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                    "`uid` TEXT NOT NULL, `usedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `avatarUri` TEXT, " +
                    "PRIMARY KEY(`accountId`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `cloud_files` (`accountId` TEXT NOT NULL, `fileId` INTEGER NOT NULL, " +
                    "`parentFileId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `isFolder` INTEGER NOT NULL, " +
                    "`size` INTEGER NOT NULL, `etag` TEXT NOT NULL, `s3KeyFlag` TEXT NOT NULL, " +
                    "`createAt` INTEGER NOT NULL, `updateAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `fileId`), " +
                    "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`accountId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `directory_states` (`accountId` TEXT NOT NULL, `dirId` INTEGER NOT NULL, " +
                    "`total` INTEGER NOT NULL, `allLoaded` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `dirId`), " +
                    "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`accountId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `transfer_tasks` (`accountId` TEXT NOT NULL, `taskId` TEXT NOT NULL, " +
                    "`fileId` INTEGER, `fileName` TEXT NOT NULL, `direction` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                    "`size` INTEGER NOT NULL, `etag` TEXT NOT NULL, `targetUri` TEXT NOT NULL, " +
                    "`downloadedBytes` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `updateTime` INTEGER NOT NULL, " +
                    "`error` TEXT, PRIMARY KEY(`accountId`, `taskId`), " +
                    "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`accountId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_cloud_files_accountId_parentFileId` ON `cloud_files` (`accountId`, `parentFileId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_directory_states_accountId` ON `directory_states` (`accountId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_transfer_tasks_accountId_state` ON `transfer_tasks` (`accountId`, `state`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            )
            // v2 的 identity hash（来自 app/schemas/.../2.json setupQueries）
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'cc52f7999859c643b501392ff3eb96f9')",
            )
            db.execSQL(
                "INSERT INTO `accounts` (`accountId`, `displayName`, `uid`, `usedBytes`, `totalBytes`) " +
                    "VALUES ('$LEGACY_ACCOUNT', '旧账户', '1', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO `transfer_tasks` (`accountId`, `taskId`, `fileId`, `fileName`, `direction`, `state`, " +
                    "`size`, `etag`, `targetUri`, `downloadedBytes`, `createTime`, `updateTime`) " +
                    "VALUES ('$LEGACY_ACCOUNT', '$LEGACY_TASK', 77, 'legacy.bin', 'DOWNLOAD', 'PAUSED', " +
                    "4096, 'etag-77', '', 4096, 1, 2)",
            )
            db.version = 2
        } finally {
            db.close()
        }
    }

    /**
     * 按 3.json 的 createSql 逐字重建 v3 结构（含 v3 identity hash 与 user_version=3）：
     * v2 基础上 transfer_tasks 多了 `destinationTree` / `segments` 两列，并新增
     * `download_segments` 表。写入一行 PAUSED 传输任务供 v3→v4 迁移用例验证数据保留。
     */
    private fun createVersion3Database() {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `accounts` (`accountId` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                    "`uid` TEXT NOT NULL, `usedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `avatarUri` TEXT, " +
                    "PRIMARY KEY(`accountId`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `cloud_files` (`accountId` TEXT NOT NULL, `fileId` INTEGER NOT NULL, " +
                    "`parentFileId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `isFolder` INTEGER NOT NULL, " +
                    "`size` INTEGER NOT NULL, `etag` TEXT NOT NULL, `s3KeyFlag` TEXT NOT NULL, " +
                    "`createAt` INTEGER NOT NULL, `updateAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `fileId`), " +
                    "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`accountId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `directory_states` (`accountId` TEXT NOT NULL, `dirId` INTEGER NOT NULL, " +
                    "`total` INTEGER NOT NULL, `allLoaded` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `dirId`), " +
                    "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`accountId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `transfer_tasks` (`accountId` TEXT NOT NULL, `taskId` TEXT NOT NULL, " +
                    "`fileId` INTEGER, `fileName` TEXT NOT NULL, `direction` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                    "`size` INTEGER NOT NULL, `etag` TEXT NOT NULL, `targetUri` TEXT NOT NULL, " +
                    // v2→v3 以 ALTER TABLE ADD COLUMN ... DEFAULT 加列，真实 v3 库的这两列带默认值，
                    // 必须逐字保留，否则 Room 校验 v4 期望 schema 时 defaultValue 不一致
                    "`destinationTree` TEXT NOT NULL DEFAULT '', `segments` INTEGER NOT NULL DEFAULT 0, " +
                    "`downloadedBytes` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `updateTime` INTEGER NOT NULL, " +
                    "`error` TEXT, PRIMARY KEY(`accountId`, `taskId`), " +
                    "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`accountId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `download_segments` (" +
                    "`accountId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `segmentIndex` INTEGER NOT NULL, " +
                    "`start` INTEGER NOT NULL, `end` INTEGER NOT NULL, `downloaded` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `taskId`, `segmentIndex`), " +
                    "FOREIGN KEY(`accountId`, `taskId`) REFERENCES `transfer_tasks`(`accountId`, `taskId`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_cloud_files_accountId_parentFileId` ON `cloud_files` (`accountId`, `parentFileId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_directory_states_accountId` ON `directory_states` (`accountId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_transfer_tasks_accountId_state` ON `transfer_tasks` (`accountId`, `state`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_download_segments_accountId_taskId` " +
                    "ON `download_segments` (`accountId`, `taskId`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            )
            // v3 的 identity hash（来自 app/schemas/.../3.json setupQueries）
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'c01aadff581392770bb59fdb2c5bd2b9')",
            )
            db.execSQL(
                "INSERT INTO `accounts` (`accountId`, `displayName`, `uid`, `usedBytes`, `totalBytes`) " +
                    "VALUES ('$LEGACY_ACCOUNT', '旧账户', '1', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO `transfer_tasks` (`accountId`, `taskId`, `fileId`, `fileName`, `direction`, `state`, " +
                    "`size`, `etag`, `targetUri`, `destinationTree`, `segments`, `downloadedBytes`, `createTime`, `updateTime`) " +
                    "VALUES ('$LEGACY_ACCOUNT', '$LEGACY_TASK', 77, 'legacy.bin', 'DOWNLOAD', 'PAUSED', " +
                    "4096, 'etag-77', '', '', 2, 4096, 1, 2)",
            )
            db.version = 3
        } finally {
            db.close()
        }
    }

    private companion object {
        const val LEGACY_ACCOUNT = "legacy-account"
        const val LEGACY_TASK = "legacy-task"
    }
}
