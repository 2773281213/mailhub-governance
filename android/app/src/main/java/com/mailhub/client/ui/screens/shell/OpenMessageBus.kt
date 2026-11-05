package com.mailhub.client.ui.screens.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知点击的进程内总线：`MainActivity` 从启动 Intent 的 [com.mailhub.client.work.MessageIdExtra]
 * 里读出邮件 id，交给这里；已登录的外壳订阅并取走它，打开「02 收件」再压栈详情页。
 *
 * 两条硬性约束：
 * 1. 只接受大于 0 的 id，其他值一律忽略；
 * 2. **取走即清空**：同一个 id 只会被处理一次；未登录时一直留在总线里，登录成功后再取。
 *
 * 总线里只有邮件 id，不接收、不记录任何其他内容，也不写日志。
 */
class OpenMessageBus {

    private val _pending = MutableStateFlow<Long?>(null)

    /** 当前等待处理的邮件 id；null 表示没有。 */
    val pending: StateFlow<Long?> = _pending.asStateFlow()

    /**
     * 投递一次通知点击，返回是否被接受。
     * id 不大于 0 时直接丢弃（通知里没有带 extra、或被系统改写成了 0）。
     */
    @Synchronized
    fun publish(messageId: Long): Boolean {
        if (messageId <= 0L) return false
        _pending.value = messageId
        return true
    }

    /** 取走当前等待处理的 id 并清空；没有时返回 null。 */
    @Synchronized
    fun consume(): Long? {
        val id = _pending.value ?: return null
        _pending.value = null
        return id
    }

    /** 清掉等待中的 id；测试之间和进程复位时用。 */
    @Synchronized
    fun reset() {
        _pending.value = null
    }

    companion object {
        /** 进程内唯一实例：`MainActivity` 投递，外壳订阅。 */
        val shared: OpenMessageBus = OpenMessageBus()
    }
}

/**
 * 已登录时取走通知里的邮件 id；未登录时保留在总线里，等登录成功后外壳再来取。
 */
fun openMessageIdToHandle(bus: OpenMessageBus, loggedIn: Boolean): Long? =
    if (loggedIn) bus.consume() else null
