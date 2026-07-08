package org.zhavoronkov.tokenpulse.startup

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.zhavoronkov.tokenpulse.service.TokenPulsePluginService
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.ui.TokenPulseConfigurable

/**
 * Startup activity to show "What's New" notification after plugin update.
 */
class WhatsNewNotificationActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settingsService = TokenPulseSettingsService.getInstance()
        val settings = settingsService.state
        val lastSeenVersion = settings.lastSeenVersion

        val currentVersion = TokenPulsePluginService.getVersion()

        // Only show notification if this is a new version
        if (lastSeenVersion != currentVersion && lastSeenVersion.isNotEmpty()) {
            showWhatsNewNotification(project, currentVersion)
            settings.lastSeenVersion = currentVersion
        } else if (lastSeenVersion.isEmpty()) {
            // First install
            settings.lastSeenVersion = currentVersion
        }
    }

    private fun showWhatsNewNotification(project: Project, version: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("TokenPulse Updates")
            .createNotification(
                "TokenPulse Updated to v$version",
                """
                <b>Thank you for using TokenPulse!</b><br/><br/>
                <b>New in v$version:</b><br/>
                • <b>ClinePass usage limits</b> — Optional 5-hour, weekly & monthly usage for Cline API key accounts<br/>
                • <b>Reliability improvements</b> — Fixed CLI connect dialog crash and Claude detection hangs<br/>
                • <b>Smart refresh tooltip</b> — Shows "Refreshing balances…" instead of "No accounts configured"<br/>
                • <b>Safe Xiaomi parsing</b> — Handles null responses from expired / stopped token plans<br/>
                • <b>General hardening</b> — Timeouts, error handling, and debug logging throughout
                """.trimIndent(),
                NotificationType.INFORMATION
            )
            .addAction(object : NotificationAction("Open Settings") {
                override fun actionPerformed(e: AnActionEvent, notification: com.intellij.notification.Notification) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, TokenPulseConfigurable::class.java)
                    notification.expire()
                }
            })
            .addAction(object : NotificationAction("View Changelog") {
                override fun actionPerformed(e: AnActionEvent, notification: com.intellij.notification.Notification) {
                    BrowserUtil.browse("https://github.com/DimazzzZ/token-pulse/blob/main/CHANGELOG.md")
                    notification.expire()
                }
            })
            .notify(project)
    }
}
