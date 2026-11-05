package com.mailhub.client.work

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.pm.PackageManager
import com.mailhub.client.data.model.MessageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 系统通知与调度：锁屏版本不带验证码、点击带上邮件 id、同一封邮件只有一条、
 * 没授权不发；周期任务的参数与 Manifest 里的服务声明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SystemChangesNotifierTest {

    private lateinit var app: Application
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(NotificationPermission.Name)
    }

    private val otpMail = MessageSummary(
        id = 9001,
        subject = "你的登录验证码",
        senderName = "Cloudflare",
        senderAddr = "noreply@cloudflare.example",
        category = "验证码",
        displayCategory = "验证码",
        otpCode = "482913",
        unread = 1,
        dateTs = 1793782440.0,
    )

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    /** 把通知里所有文字类 extra 拼起来，用来断言某段内容有没有出现。 */
    private fun allText(notification: Notification): String =
        notification.extras.keySet().joinToString(" ") { key ->
            notification.extras.getCharSequence(key)?.toString().orEmpty()
        }

    @Test
    fun otpNotificationHidesTheCodeOnTheLockScreen() {
        SystemChangesNotifier(app).notify(otpMail, NotifyChannel.Otp)

        val notification = posted().single()
        assertEquals(NotifyChannel.Otp.id, notification.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(allText(notification).contains("482913"))
        assertNotNull(notification.publicVersion)
        val public = notification.publicVersion!!
        assertEquals("收到新验证码", public.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val publicText = allText(public)
        assertFalse(publicText, publicText.contains("482913"))
        assertFalse(publicText, publicText.contains("Cloudflare"))
        assertFalse(publicText, publicText.contains("你的登录验证码"))
    }

    @Test
    fun tappingOpensTheAppWithTheMessageId() {
        SystemChangesNotifier(app).notify(otpMail, NotifyChannel.Otp)

        val intent = shadowOf(posted().single().contentIntent).savedIntent
        assertEquals(9001L, intent.getLongExtra(MessageIdExtra, 0))
        assertEquals("com.mailhub.client.MainActivity", intent.component?.className)
    }

    @Test
    fun channelsAreCreatedOnFirstUse() {
        SystemChangesNotifier(app).notify(otpMail, NotifyChannel.Otp)

        NotifyChannel.entries.forEach { channel ->
            assertEquals(channel.label, manager.getNotificationChannel(channel.id)?.name?.toString())
        }
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("otp")?.importance)
    }

    @Test
    fun theSameMailTwiceLeavesOneNotification() {
        val notifier = SystemChangesNotifier(app)
        notifier.notify(otpMail, NotifyChannel.Otp)
        notifier.notify(otpMail, NotifyChannel.Otp)

        assertEquals(1, posted().size)
    }

    @Test
    fun nothingIsPostedWithoutPermission() {
        shadowOf(app).denyPermissions(NotificationPermission.Name)

        SystemChangesNotifier(app).notify(otpMail, NotifyChannel.Otp)

        assertTrue(posted().isEmpty())
        assertTrue(NotificationPermission.needsRequest(app))
    }

    @Test
    fun scheduleCreatesOnePersistedPeriodicJobThatNeedsNetwork() {
        val scheduler = app.getSystemService(JobScheduler::class.java)

        ChangesScheduler.schedule(app)
        ChangesScheduler.schedule(app)

        assertEquals(1, scheduler.allPendingJobs.size)
        val job = scheduler.getPendingJob(ChangesScheduler.JobId)!!
        assertTrue(job.isPeriodic)
        assertEquals(ChangesScheduler.PeriodMillis, job.intervalMillis)
        assertTrue(job.isPersisted)
        @Suppress("DEPRECATION")
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)

        ChangesScheduler.cancel(app)
        assertNull(scheduler.getPendingJob(ChangesScheduler.JobId))
    }

    @Test
    fun jobServiceIsOnlyBindableByTheSystem() {
        val info = app.packageManager.getServiceInfo(
            ComponentName(app, ChangesJobService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        assertFalse(info.exported)
    }
}
