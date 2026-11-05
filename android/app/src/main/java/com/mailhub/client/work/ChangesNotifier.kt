package com.mailhub.client.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mailhub.client.MainActivity
import com.mailhub.client.R
import com.mailhub.client.data.model.MessageSummary

/** 发通知的出口；同步逻辑只认这个接口，测试里换成记录调用的假实现。 */
fun interface ChangesNotifier {
    fun notify(item: MessageSummary, channel: NotifyChannel)
}

/** 点通知打开 App 时带上的邮件 id，由集成任务读取后跳到详情页。 */
const val MessageIdExtra: String = "message_id"

/** API 33 起通知要运行时授权；更早的系统安装即授予。 */
object NotificationPermission {

    /** 写成字面量：Manifest.permission.POST_NOTIFICATIONS 是 API 33 的字段，minSdk 26 上 lint 会提示。 */
    const val Name: String = "android.permission.POST_NOTIFICATIONS"

    /** 是否需要弹出运行时申请。真正弹出由集成任务完成。 */
    fun needsRequest(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Name) != PackageManager.PERMISSION_GRANTED
}

/**
 * 系统通知：
 * - 三个渠道在第一次发通知时创建（重复创建是幂等的）；
 * - 所有通知都是 VISIBILITY_PRIVATE，锁屏上只显示「收到新验证码」这类公开版本，
 *   不显示发件人、主题和验证码；
 * - 通知 id 就是邮件 id，同一封邮件只会有一条；
 * - 点击打开 [MainActivity]，extra 里带 [MessageIdExtra]。
 *
 * 不写日志：通知内容里有邮件主题和验证码。
 */
class SystemChangesNotifier(context: Context) : ChangesNotifier {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    override fun notify(item: MessageSummary, channel: NotifyChannel) {
        // 没有授权时什么都不发；游标照常前进，之后授权也不会补发一大串旧通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels()
        val text = notificationTextOf(item, channel)
        val id = notificationIdOf(item.id)
        val publicVersion = NotificationCompat.Builder(appContext, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.publicTitle)
            .build()
        val notification = NotificationCompat.Builder(appContext, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setWhen((item.dateTs * 1000).toLong())
            .setShowWhen(item.dateTs > 0)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent(item.id, id))
            .build()
        manager.notify(id, notification)
    }

    private fun ensureChannels() {
        NotifyChannel.entries.forEach { channel ->
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(channel.id, importanceOf(channel))
                    .setName(channel.label)
                    .build(),
            )
        }
    }

    /** 验证码和安全提醒要立刻看到，用高优先级；重要邮件用默认优先级。 */
    private fun importanceOf(channel: NotifyChannel): Int = when (channel) {
        NotifyChannel.Otp, NotifyChannel.Security -> NotificationManagerCompat.IMPORTANCE_HIGH
        NotifyChannel.Important -> NotificationManagerCompat.IMPORTANCE_DEFAULT
    }

    /** requestCode 用通知 id，否则不同邮件的 PendingIntent 会被当成同一个，extra 互相覆盖。 */
    private fun openIntent(messageId: Long, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MessageIdExtra, messageId)
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
