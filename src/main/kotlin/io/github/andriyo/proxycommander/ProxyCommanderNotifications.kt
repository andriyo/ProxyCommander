package io.github.andriyo.proxycommander

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * Single entry point for all plugin notifications. The "ProxyCommander" group is declared as a
 * <notificationGroup> in plugin.xml, so balloons are configurable under Settings > Notifications
 * and the platform no longer warns about an unregistered group.
 */
internal object ProxyCommanderNotifications {
    const val GROUP_ID = "ProxyCommander"

    fun create(message: String, type: NotificationType): Notification =
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(message, type)

    fun notify(message: String, type: NotificationType, project: Project? = null) {
        if (message.isBlank()) {
            return
        }
        val notification = create(message, type)
        ApplicationManager.getApplication().invokeLater {
            if (project?.isDisposed == true) {
                notification.expire()
                return@invokeLater
            }
            if (project != null) {
                Notifications.Bus.notify(notification, project)
            } else {
                Notifications.Bus.notify(notification)
            }
        }
    }
}
