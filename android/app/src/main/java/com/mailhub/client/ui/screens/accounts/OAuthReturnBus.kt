package com.mailhub.client.ui.screens.accounts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内的 OAuth 返回总线：`MainActivity` 从深链 `mailhub://oauth/done?tx=…` 里读出事务 id，
 * 交给这里；等待中的添加账户流程订阅这里，收到自己的事务 id 就立刻轮询一次结果。
 *
 * 两条硬性约束：
 * 1. 只接受**与当前等待中的事务 id 相同**的深链，其他值一律忽略（返回 false）；
 * 2. 深链里只有事务 id，这里只转发 id，不接收、不记录任何令牌，也不写日志。
 *
 * 每次接受都会让 [Return.seq] 递增：同一条深链被重复投递时状态仍然会变化，
 * 订阅方不会因为「值没变」而漏掉一次立即轮询。
 */
class OAuthReturnBus {

    /** 一次被接受的返回：事务 id + 递增序号。 */
    data class Return(val txId: String, val seq: Long)

    private val _returns = MutableStateFlow<Return?>(null)

    /** 当前最近一次被接受的返回，null 表示还没有。 */
    val returns: StateFlow<Return?> = _returns.asStateFlow()

    /** 当前等待中的事务 id；null 表示没有流程在等。 */
    @Volatile
    private var expectedTx: String? = null

    /** 登记当前等待中的事务 id；发起浏览器 OAuth 之前调用。 */
    @Synchronized
    fun expect(txId: String) {
        expectedTx = txId.takeIf { it.isNotBlank() }
    }

    /** 等待结束（成功、失败、超时、页面退出）时调用，之后到达的深链都会被忽略。 */
    @Synchronized
    fun clear() {
        expectedTx = null
    }

    /**
     * 投递一次深链返回，返回是否被接受。
     * 空白 id 或与等待中的事务 id 不一致时一律丢弃。
     */
    @Synchronized
    fun publish(txId: String): Boolean {
        val expected = expectedTx
        if (txId.isBlank() || expected == null || txId != expected) {
            return false
        }
        val next = (_returns.value?.seq ?: 0L) + 1L
        _returns.value = Return(txId = txId, seq = next)
        return true
    }

    /** 清掉等待状态和最近一次返回；页面重新进入或测试之间用它复位。 */
    @Synchronized
    fun reset() {
        expectedTx = null
        _returns.value = null
    }

    companion object {
        /** 进程内唯一实例：`MainActivity` 投递，添加账户流程订阅。 */
        val shared: OAuthReturnBus = OAuthReturnBus()
    }
}
