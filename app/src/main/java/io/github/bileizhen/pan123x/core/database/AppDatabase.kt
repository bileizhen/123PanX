package io.github.bileizhen.pan123x.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        CloudFileEntity::class,
        DirectoryStateEntity::class,
        TransferTaskEntity::class,
        DownloadSegmentEntity::class,
        UploadPartEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun cloudFileDao(): CloudFileDao
    abstract fun directoryStateDao(): DirectoryStateDao
    abstract fun transferTaskDao(): TransferTaskDao
    abstract fun downloadSegmentDao(): DownloadSegmentDao
    abstract fun uploadPartDao(): UploadPartDao
    abstract fun cacheMaintenanceDao(): CacheMaintenanceDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java, "pan123x.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `shareKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `sharePassword` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Add public cloud metadata without changing accounts, credentials or transfer rows. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("hasCloudInfo", "vip", "vipLevel", "permanentBytes", "temporaryBytes",
                    "professionalTotalBytes", "professionalUsedBytes", "standardTotalBytes",
                    "standardUsedBytes", "fileCount", "directTrafficBytes").forEach { column ->
                    db.execSQL("ALTER TABLE `accounts` ADD COLUMN `$column` INTEGER NOT NULL DEFAULT 0")
                }
                listOf("maskedPassport", "vipExpire").forEach { column ->
                    db.execSQL("ALTER TABLE `accounts` ADD COLUMN `$column` TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        /**
         * v1 -> v2（禁止破坏性迁移）：
         * 1. cloud_files 的 createAt / updateAt 由 TEXT 改为 INTEGER（epoch 毫秒，日期排序
         *    需要可比较数值）。表重建迁移：新建 Long 列表 -> 复制 -> drop -> rename -> 重建索引。
         *    旧缓存中的字符串日期没有可靠的解析口径，统一置 0——开发期数据的取舍，下一次
         *    目录全量刷新会整体覆盖这些行，不影响正确性。
         * 2. 新增 directory_states（目录缓存元数据，accountId 外键级联删除，同 cloud_files 风格）。
         * 表结构与索引名严格对照 schema 导出（app/schemas 下 1.json 与 KSP 生成的 2.json）。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_cloud_files` (" +
                        "`accountId` TEXT NOT NULL, `fileId` INTEGER NOT NULL, `parentFileId` INTEGER NOT NULL, " +
                        "`fileName` TEXT NOT NULL, `isFolder` INTEGER NOT NULL, `size` INTEGER NOT NULL, " +
                        "`etag` TEXT NOT NULL, `s3KeyFlag` TEXT NOT NULL, `createAt` INTEGER NOT NULL, `updateAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`accountId`, `fileId`), " +
                        "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`accountId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                // 旧字符串日期不解析、统一置 0（见方法注释中的取舍说明）
                db.execSQL(
                    "INSERT INTO `_new_cloud_files` (`accountId`, `fileId`, `parentFileId`, `fileName`, `isFolder`, `size`, `etag`, `s3KeyFlag`, `createAt`, `updateAt`) " +
                        "SELECT `accountId`, `fileId`, `parentFileId`, `fileName`, `isFolder`, `size`, `etag`, `s3KeyFlag`, 0, 0 FROM `cloud_files`",
                )
                db.execSQL("DROP TABLE `cloud_files`")
                db.execSQL("ALTER TABLE `_new_cloud_files` RENAME TO `cloud_files`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cloud_files_accountId_parentFileId` ON `cloud_files` (`accountId`, `parentFileId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `directory_states` (" +
                        "`accountId` TEXT NOT NULL, `dirId` INTEGER NOT NULL, `total` INTEGER NOT NULL, " +
                        "`allLoaded` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`accountId`, `dirId`), " +
                        "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`accountId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_directory_states_accountId` ON `directory_states` (`accountId`)",
                )
            }
        }

        /**
         * v2 -> v3（禁止破坏性迁移）：
         * 1. transfer_tasks 增列 destinationTree（SAF 目录树 uri，空串 = 应用内目录）与
         *    segments（分段数，仅列表展示用）。两列在实体上带 @ColumnInfo(defaultValue)，
         *    此处必须给出同名字面量默认值，否则 Room 打开数据库时 schema 校验不一致。
         * 2. 新增 download_segments（下载分段进度）。分段计划行数可变且会被动态尾段拆分改写，
         *    独立成表才能做到"整体替换"而不重写任务行。复合主键 + 复合外键
         *    指向 transfer_tasks(accountId, taskId) 并 CASCADE，任务删除即级联清理，无孤儿行。
         * SQL 逐字对照 KSP 导出的 app/schemas/.../3.json（列顺序、类型、默认值、外键动作）。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `destinationTree` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `segments` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `download_segments` (" +
                        "`accountId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `segmentIndex` INTEGER NOT NULL, " +
                        "`start` INTEGER NOT NULL, `end` INTEGER NOT NULL, `downloaded` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`accountId`, `taskId`, `segmentIndex`), " +
                        "FOREIGN KEY(`accountId`, `taskId`) REFERENCES `transfer_tasks`(`accountId`, `taskId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_download_segments_accountId_taskId` " +
                        "ON `download_segments` (`accountId`, `taskId`)",
                )
            }
        }

        /**
         * v3 -> v4（M5，， 禁止破坏性迁移）：
         * 1. transfer_tasks 追加上传会话 8 列（实体侧 @ColumnInfo(defaultValue) 与此处 DEFAULT
         *    字面量逐字一致，否则 Room 打开数据库时 schema 校验失败——M4 已踩过）：
         *    - `s3KeyFlag`：M4 后置的下载恢复字段（上传不使用，保持空串默认）；
         *    - `parentFileId`：上传目标目录；
         *    - `bucket` / `storageNode` / `uploadKey` / `uploadId`：S3 multipart 会话；
         *    - `sourceMtime`：上传源文件修改时间（续传校验）；
         *    - `blockSize`：会话内分片大小（固定 5MiB，UploadPartPlan.BLOCK_SIZE）。
         * 2. 新增 upload_parts（上传分片进度）。与 download_segments 同构：复合主键 + 复合外键
         *    指向 transfer_tasks(accountId, taskId) 并 CASCADE，任务删除即级联清理，无孤儿行。
         * SQL 逐字对照 KSP 导出的 app/schemas/.../4.json（列顺序无关，TableInfo 按列名比较）。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `s3KeyFlag` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `parentFileId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `bucket` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `storageNode` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `uploadKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `uploadId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `sourceMtime` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `transfer_tasks` ADD COLUMN `blockSize` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `upload_parts` (`accountId` TEXT NOT NULL, `taskId` TEXT NOT NULL, " +
                        "`partNumber` INTEGER NOT NULL, `size` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`accountId`, `taskId`, `partNumber`), " +
                        "FOREIGN KEY(`accountId`, `taskId`) REFERENCES `transfer_tasks`(`accountId`, `taskId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_upload_parts_accountId_taskId` " +
                        "ON `upload_parts` (`accountId`, `taskId`)",
                )
            }
        }
    }
}
