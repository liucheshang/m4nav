package com.m4nav

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.atomic.AtomicLong

/**
 * 抓高德通知：把 title 与 text 拼起来交给 NavService。
 * 需要用户在系统设置里授予"通知使用权"。
 */
class NotifListener : NotificationListenerService() {

    companion object {
        private const val PKG_AMAP = "com.autonavi.minimap"
        private const val PKG_BAIDU = "com.baidu.BaiduMap"
        private val lastAt = AtomicLong(0L)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (!pkg.contains("autonavi") && !pkg.equals(PKG_AMAP, true) &&
            !pkg.contains("baidumap", true) && !pkg.equals(PKG_BAIDU, true)
        ) {
            return
        }
        val n: Notification = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val combined = listOf(title, text, big).filter { it.isNotBlank() }.joinToString(" ")
        if (combined.isBlank()) return

        // 同一秒内同一条通知可能重复回调，粗过滤一下
        val now = System.currentTimeMillis()
        if (now - lastAt.get() < 800L) return
        lastAt.set(now)

        NavService.instance?.handleText(combined)
            ?: NavService.onLog?.invoke("服务未启动，丢弃：$combined")
    }
}
