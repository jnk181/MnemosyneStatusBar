package com.mnemosynesuite.mnemosynestatusbar

import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification


class MediaNotificationService : NotificationListenerService() {
    private val messagingApps = setOf(
        "com.android.providers.telephony",
        "com.google.android.apps.messaging",
        "com.viber.voip",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.discord"
    )

    // Update with RankingMap parameter
    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        android.util.Log.d("MnemosyneDebug", "Notification received from: ${sbn.packageName}")
        checkStatus()
    }

    // Update with RankingMap parameter
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {
        checkStatus()
    }

    private fun checkStatus() {
        // Use activeNotifications property
        val activeNotifs = activeNotifications ?: return

        val isMediaPlaying = activeNotifs.any {
            it.notification.extras.containsKey("android.mediaSession")
        }

        val hasNewMessage = activeNotifs.any {
            messagingApps.contains(it.packageName)
        }

        val intent = Intent("com.mnemosynesuite.STATUS_UPDATE")
        intent.putExtra("media_playing", isMediaPlaying)
        intent.putExtra("new_message", hasNewMessage)
        sendBroadcast(intent)
    }
}