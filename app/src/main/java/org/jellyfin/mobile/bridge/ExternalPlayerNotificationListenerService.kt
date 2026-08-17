package org.jellyfin.mobile.bridge

import android.service.notification.NotificationListenerService
import org.koin.android.ext.android.inject

/**
 * Grants access to active media sessions when explicitly enabled by the user in Android settings.
 * Notification contents are intentionally not read or processed.
 */
class ExternalPlayerNotificationListenerService : NotificationListenerService() {
    private val mediaSessionObserver: VlcMediaSessionObserver by inject()

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionObserver.onNotificationListenerConnected()
    }

    override fun onListenerDisconnected() {
        mediaSessionObserver.onNotificationListenerDisconnected()
        super.onListenerDisconnected()
    }
}
