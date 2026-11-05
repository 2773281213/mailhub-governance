package com.mailhub.client.ui.screens.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.mailhub.client.data.session.SessionState

/**
 * 登录代际的 ViewModelStore 持有者。
 *
 * 它是 Activity 级的 ViewModel：Activity 因旋转等配置变化重建时，它连同里面的 ViewModelStore
 * 一起被系统保留下来，页面状态不会因为转一下屏幕就丢。原来挂在 Activity 上的 ViewModel 能挺过
 * 配置变化，靠的就是同一套机制。
 *
 * 代际规则与 [isNewLoginGeneration] 一致：
 * - 新代际（未登录 → 已登录）换一份全新的 store，旧的一次性清空；
 * - 已登录内部换令牌沿用同一份 store；
 * - 进入 [SessionState.LoggedOut] 时清空，下次登录前不会看到上一台服务器的数据；
 * - 持有者自己被清理时（[onCleared]）清空。
 *
 * [ownerFor] 每次重组都可能被调用，所以同一个状态重复调用必须幂等：返回同一个 owner，
 * 不清空、也不换 store。
 */
class ShellStoreHolder : ViewModel() {

    private var lastState: SessionState? = null

    private var current: SessionOwner = SessionOwner(ViewModelStore())

    /** 当前代际的 owner；同一个代际里始终是同一个实例。 */
    val owner: ViewModelStoreOwner get() = current

    /** 按会话状态推进代际，返回当前代际的 owner。 */
    fun ownerFor(state: SessionState): ViewModelStoreOwner {
        val previous = lastState
        if (previous == state) {
            return current
        }
        lastState = state
        when {
            state is SessionState.LoggedOut -> current.viewModelStore.clear()
            isNewLoginGeneration(previous, state) -> {
                current.viewModelStore.clear()
                current = SessionOwner(ViewModelStore())
            }
        }
        return current
    }

    override fun onCleared() {
        current.viewModelStore.clear()
    }
}

/** 只把一份 ViewModelStore 交出去；store 本身由 [ShellStoreHolder] 管理。 */
private class SessionOwner(store: ViewModelStore) : ViewModelStoreOwner {

    override val viewModelStore: ViewModelStore = store
}
