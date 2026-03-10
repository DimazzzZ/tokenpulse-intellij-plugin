package org.zhavoronkov.tokenpulse.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object TokenPulseNotifier {
    fun notifyInfo(project: Project?, content: String) {
        val redacted = SecretRedactor.redact(content)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("TokenPulse")
            .createNotification(redacted, NotificationType.INFORMATION)
            .notify(project)
    }

    fun notifyWarning(project: Project?, content: String) {
        val redacted = SecretRedactor.redact(content)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("TokenPulse")
            .createNotification(redacted, NotificationType.WARNING)
            .notify(project)
    }

    fun notifyError(project: Project?, content: String) {
        val redacted = SecretRedactor.redact(content)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("TokenPulse")
            .createNotification(redacted, NotificationType.ERROR)
            .notify(project)
    }
}
