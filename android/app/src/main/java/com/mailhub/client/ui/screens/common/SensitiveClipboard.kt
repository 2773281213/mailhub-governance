package com.mailhub.client.ui.screens.common

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 敏感文本在剪贴板里最多停留多久（毫秒）。 */
const val SensitiveClipTtlMillis: Long = 60_000L

/**
 * 剪贴板的最小接口：真机用 [AndroidClipboardHandle]，测试里换成假实现，
 * 这样 [SensitiveClipboard] 不需要 Android 运行时就能跑单元测试。
 */
interface ClipboardHandle {

    /** 写入文本；[sensitive] 为真时按 API 33+ 的约定标记为敏感内容。 */
    fun copy(text: String, sensitive: Boolean)

    /** 剪贴板里当前的纯文本，读不到时返回 null。 */
    fun primaryText(): String?

    /** 清空剪贴板。 */
    fun clear()
}

/**
 * 敏感文本剪贴板：验证码一类内容写进剪贴板后，[ttlMillis] 毫秒后自动清空，
 * 避免长时间留在剪贴板里被其它应用读到。
 *
 * 剪贴板对象、作用域和时钟都从构造函数注入，测试里用虚拟时间跑满 60 秒即可断言清除行为。
 * [scope] 故意与页面作用域解耦：用户复制完马上退出页面，清除也要照做。
 */
class SensitiveClipboard(
    private val clipboard: ClipboardHandle,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMillis: Long = SensitiveClipTtlMillis,
) {

    /**
     * 复制一段敏感文本，并安排在到期后清除。
     * 到期时剪贴板里仍然是这段文本才清空；用户中途复制了别的内容就不动它。
     */
    fun copy(text: String) {
        if (text.isEmpty()) return
        clipboard.copy(text, sensitive = true)
        val deadline = clock() + ttlMillis
        scope.launch {
            delay(ttlMillis)
            if (clock() >= deadline && clipboard.primaryText() == text) {
                clipboard.clear()
            }
        }
    }
}

/**
 * 真机剪贴板：API 33 起给内容打上 [ClipDescription.EXTRA_IS_SENSITIVE]，
 * 系统就不会在剪贴板预览里显示它；API 28 起用 clearPrimaryClip() 清空。
 */
class AndroidClipboardHandle(
    private val clipboard: ClipboardManager,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
) : ClipboardHandle {

    override fun copy(text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(ClipLabel, text)
        if (sensitive && apiLevel >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    override fun primaryText(): String? {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    // apiLevel 是可注入的（测试要覆盖 API 26 的退路），lint 看不出这里已经有版本门，
    // 所以显式压掉 NewApi 报错；运行时判断仍然按 API 28 起用 clearPrimaryClip()。
    @SuppressLint("NewApi")
    override fun clear() {
        if (apiLevel >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText(ClipLabel, ""))
        }
    }

    private companion object {
        const val ClipLabel = "MailHub"
    }
}

/**
 * 页面层用：取当前 Context 的剪贴板。作用域不跟随页面走，页面关掉后 60 秒的清除仍会执行。
 */
@Composable
fun rememberSensitiveClipboard(): SensitiveClipboard {
    val context = LocalContext.current
    return remember(context) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        SensitiveClipboard(
            clipboard = AndroidClipboardHandle(manager),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}
