package com.pockettavern.app.ui.screens.girlfriend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pockettavern.app.R
import com.pockettavern.app.data.local.SettingsDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 小女友 24/7 主动感知前台服务。
 * - 常驻通知条
 * - 按无聊值定期判断是否主动联系用户
 * - 若 LLM 返回非空 text，注入当前聊天
 * 开关控制：SettingsDataStore.KEY_GIRLFRIEND_AWARENESS_ENABLED
 */
@AndroidEntryPoint
class GirlfriendAwarenessService : Service() {

    @Inject lateinit var settingsDataStore: SettingsDataStore

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var requestedMessagesJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (wakeLock?.isHeld != true) {
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GirlfriendAwareness::Loop")?.apply {
                acquire(60 * 60 * 1000L)
            }
        }
        startLoop()
        val requestedCount = intent?.getIntExtra(EXTRA_MESSAGE_COUNT, 0) ?: 0
        if (requestedCount > 0) {
            val intervalSeconds = intent?.getIntExtra(EXTRA_MESSAGE_INTERVAL_SECONDS, 20) ?: 20
            requestedMessagesJob?.cancel()
            requestedMessagesJob = scope.launch {
                sendRequestedMessages(requestedCount.coerceIn(1, 5), intervalSeconds.coerceIn(10, 300))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLoop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLoop() {
        stopLoop()
        loopJob = scope.launch {
            while (isActive) {
                run loop@{
                    try {
                        val enabled = settingsDataStore.isGirlfriendAwarenessEnabled()
                        if (!enabled) { delay(5000L); return@loop }

                        val periodSec = settingsDataStore.getGirlfriendAwarenessIntervalSec()
                        val boredom = GirlfriendProactiveManager.tickBoredom(
                            this@GirlfriendAwarenessService
                        )
                        if (boredom < com.pockettavern.app.data.girlfriend.GirlfriendMemoryStore.BOREDOM_PROACTIVE_THRESHOLD) {
                            delay(periodSec * 1000L)
                            return@loop
                        }
                        val config = settingsDataStore.getLastKnownApiConfig()
                        if (config == null) { delay(periodSec * 1000L); return@loop }

                        val boredomHint = when {
                            boredom >= 80 -> "你非常想念老大了。主动发一条有内容、自然亲近的消息，但不要声称操作过手机或文件。"
                            boredom >= 60 -> "你有点无聊，也开始想老大了。可以主动找他聊一句。"
                            else -> ""
                        }
                        val result = com.pockettavern.app.data.repository.generateProactiveCheck(boredomHint, config)
                        if (result.isNotEmpty()) {
                            val delivered = GirlfriendProactiveManager.injectMessage(
                                this@GirlfriendAwarenessService,
                                result
                            )
                            if (delivered) {
                                showProactiveNotification(result)
                                GirlfriendProactiveManager.resetBoredom(this@GirlfriendAwarenessService)
                                delay(30_000L)
                                return@loop
                            }
                        }
                        delay(periodSec * 1000L)
                    } catch (e: Exception) {
                        delay(30_000L)
                    }
                }
            }
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** 用户明确要求的主动消息：首条稍作延迟，后续按间隔写入小女友聊天并发通知。 */
    private suspend fun sendRequestedMessages(count: Int, intervalSeconds: Int) {
        val config = settingsDataStore.getLastKnownApiConfig()
        if (config == null) {
            val text = "老大，我想主动找你来着，但还没有可用的 API 配置。先去小女友设置里配一下吧。"
            if (GirlfriendProactiveManager.injectMessage(this, text)) showProactiveNotification(text)
            if (!settingsDataStore.isGirlfriendAwarenessEnabled()) stopSelf()
            return
        }
        repeat(count) { index ->
            delay(if (index == 0) 8_000L else intervalSeconds * 1000L)
            val generated = com.pockettavern.app.data.repository.generateProactiveCheck(
                contextHint = "",
                config = config,
                forcedInstruction = "这是用户要求的第 ${index + 1}/$count 条主动消息。"
            )
            val text = generated.ifBlank {
                "老大，我刚才想主动找你，但这次没连上模型……等网络稳一点我再来。"
            }
            if (GirlfriendProactiveManager.injectMessage(this, text)) {
                showProactiveNotification(text, index)
                GirlfriendProactiveManager.resetBoredom(this)
            }
        }
        if (!settingsDataStore.isGirlfriendAwarenessEnabled()) stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "girlfriend_awareness_channel"
        private const val MESSAGE_CHANNEL_ID = "girlfriend_message_channel"
        private const val NOTIFICATION_ID = 2001
        private const val MESSAGE_NOTIFICATION_ID = 2002
        private const val EXTRA_MESSAGE_COUNT = "message_count"
        private const val EXTRA_MESSAGE_INTERVAL_SECONDS = "message_interval_seconds"
        private const val CHANNEL_NAME = "小女友主动联系"

        fun start(context: Context) {
            val intent = Intent(context, GirlfriendAwarenessService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GirlfriendAwarenessService::class.java))
        }

        fun requestMessages(context: Context, count: Int, intervalSeconds: Int = 20) {
            val intent = Intent(context, GirlfriendAwarenessService::class.java)
                .putExtra(EXTRA_MESSAGE_COUNT, count.coerceIn(1, 5))
                .putExtra(EXTRA_MESSAGE_INTERVAL_SECONDS, intervalSeconds.coerceIn(10, 300))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "小女友正在等待无聊值或你的主动消息指令"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
            nm.createNotificationChannel(
                NotificationChannel(
                    MESSAGE_CHANNEL_ID,
                    "小女友主动消息",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "无聊值升高或你要求主动发消息时提醒你"
                    setShowBadge(true)
                }
            )
        }
    }

    private fun showProactiveNotification(text: String, idOffset: Int = 0) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val clean = text.trim().take(500)
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle("白音来找你了")
            .setContentText(clean.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(clean))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(MESSAGE_NOTIFICATION_ID + idOffset, notification)
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("小鲸鱼喜欢你")
            .setContentText("主动联系已开启，无聊时她会来找你")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
