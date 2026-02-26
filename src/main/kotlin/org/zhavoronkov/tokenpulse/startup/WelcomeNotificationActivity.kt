package org.zhavoronkov.tokenpulse.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.ui.TokenPulseConfigurable
import org.zhavoronkov.tokenpulse.ui.TokenPulseDashboardDialog

/**
 * Startup activity that shows a welcome notification for first-time users.
 */
class WelcomeNotificationActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settingsService = TokenPulseSettingsService.getInstance()
        val settings = settingsService.state

        if (!settings.hasSeenWelcome) {
            showWelcomeNotification(project)
            settings.hasSeenWelcome = true
        }
    }

    private fun showWelcomeNotification(project: Project) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("TokenPulse Notifications")
            .createNotification(
                "Welcome to TokenPulse!",
                """
                <html>
                Track your AI token balances directly in the status bar.
                <ol style='margin-top: 4px; margin-bottom: 0px;'>
                  <li>Configure your AI provider accounts</li>
                  <li>Set your preferred refresh interval</li>
                  <li>Monitor total balance in the status bar</li>
                </ol>
                </html>
                """.trimIndent(),
                NotificationType.INFORMATION
            )
            .addAction(object : NotificationAction("Show Dashboard") {
                override fun actionPerformed(e: AnActionEvent, notification: com.intellij.notification.Notification) {
                    TokenPulseDashboardDialog(project).show()
                    notification.expire()
                }
            })
            .addAction(object : NotificationAction("Open Settings") {
                override fun actionPerformed(e: AnActionEvent, notification: com.intellij.notification.Notification) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, TokenPulseConfigurable::class.java)
                    notification.expire()
                }
            })
            .notify(project)
    }
}
