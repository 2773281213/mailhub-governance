package com.mailhub.client.ui.screens.accounts

import com.mailhub.client.data.accounts.AccountsRepository
import com.mailhub.client.data.accounts.ConnectionTest
import com.mailhub.client.data.model.Account

/**
 * 账户列表的数据接口：真实实现转发到 [AccountsRepository]，
 * 测试里注入假实现，直接断言发出了哪些请求、以及删除是否经过确认。
 */
interface AccountsGateway {

    suspend fun accounts(): Result<List<Account>>

    suspend fun syncAll(): Result<Unit>

    suspend fun sync(accountId: Int): Result<Unit>

    /** 测试连接：后端连接失败时返回 ok=false 的结论，不是异常。 */
    suspend fun test(accountId: Int): Result<ConnectionTest>

    suspend fun delete(accountId: Int): Result<Unit>
}

/** 真实实现：把 Repository 的 Result<OkResponse> 收敛成只关心成功的 Result<Unit>。 */
fun accountsGatewayOf(repository: AccountsRepository): AccountsGateway = object : AccountsGateway {

    override suspend fun accounts(): Result<List<Account>> = repository.accounts()

    override suspend fun syncAll(): Result<Unit> = repository.syncAll().map { }

    override suspend fun sync(accountId: Int): Result<Unit> = repository.syncAccount(accountId).map { }

    override suspend fun test(accountId: Int): Result<ConnectionTest> =
        repository.testAccount(accountId)

    override suspend fun delete(accountId: Int): Result<Unit> =
        repository.deleteAccount(accountId).map { }
}
