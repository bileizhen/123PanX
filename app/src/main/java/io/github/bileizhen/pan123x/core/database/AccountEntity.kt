package io.github.bileizhen.pan123x.core.database

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

/** Public metadata only. Credentials belong in the encrypted credential store in M1. */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val accountId: String,
    val displayName: String,
    val uid: String = "",
    val usedBytes: Long = 0,
    val totalBytes: Long = 0,
    val avatarUri: String? = null,
    @ColumnInfo(defaultValue = "0") val hasCloudInfo: Boolean = false,
    @ColumnInfo(defaultValue = "''") val maskedPassport: String = "",
    @ColumnInfo(defaultValue = "0") val vip: Boolean = false,
    @ColumnInfo(defaultValue = "0") val vipLevel: Int = 0,
    @ColumnInfo(defaultValue = "''") val vipExpire: String = "",
    @ColumnInfo(defaultValue = "0") val permanentBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val temporaryBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val professionalTotalBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val professionalUsedBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val standardTotalBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val standardUsedBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val fileCount: Long = 0,
    @ColumnInfo(defaultValue = "0") val directTrafficBytes: Long = 0,
)
