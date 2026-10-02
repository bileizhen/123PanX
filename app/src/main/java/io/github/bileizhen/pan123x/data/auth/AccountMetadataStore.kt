package io.github.bileizhen.pan123x.data.auth

import io.github.bileizhen.pan123x.core.database.AccountDao
import io.github.bileizhen.pan123x.core.database.AccountEntity

/**
 * 账户公开元数据（昵称 / uid / 容量 / 头像）的读写接口。
 *
 * 之所以在 AuthRepository 与 Room 之间加这层窄接口：AuthRepository 的行为单测必须跑在
 * 纯 JVM 上，直接依赖 [AccountDao] 会把 Room 的注解处理与 Android 依赖
 * 拖进普通单测；生产侧由 [RoomAccountMetadataStore] 直通 DAO，测试侧用内存实现即可。
 */
interface AccountMetadataStore {
    suspend fun upsert(account: AccountEntity)
    suspend fun get(accountId: String): AccountEntity?
}

/** 薄封装：只做接口到 DAO 的直通，不附加缓存、转换或线程调度。 */
class RoomAccountMetadataStore(private val dao: AccountDao) : AccountMetadataStore {
    override suspend fun upsert(account: AccountEntity) = dao.upsert(account)
    override suspend fun get(accountId: String): AccountEntity? = dao.get(accountId)
}
