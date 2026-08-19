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
                <b>What&rsquo;s new in v$version:</b><br/>
                • <b>DeepSeek provider</b> &mdash; track your DeepSeek API usage and quota<br/>
                • <b>GitHub Copilot provider</b> &mdash; personal and organization budget tracking<br/>
                • <b>Platform raised to IntelliJ 2025.3</b> &mdash; required for forward compatibility<br/>
                • <b>Build hardened</b> &mdash; non-public API and Detekt now gate the build<br/>
                • <b>UI fix</b> &mdash; Add/Edit Account dialog hint no longer floods the log or gets stuck tall
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
                    BrowserUtil.browse("https://github.com/DimazzzZ/tokenpulse-intellij-plugin/blob/main/CHANGELOG.md")
                    notification.expire()
                }
            })
            .notify(project)
    }
}
