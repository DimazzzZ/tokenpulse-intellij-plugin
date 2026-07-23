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
                <b>New in v$version &mdash; a redesigned status-bar tooltip</b><br/>
                Hover the status bar for an all-new, native view of every quota:<br/>
                • <b>Real progress bars</b> for each provider and account &mdash; no more plain text<br/>
                • <b>Theme-aware colors</b> that turn orange, then red as you approach a limit<br/>
                • <b>Humanized reset times</b> like &ldquo;Today 14:30&rdquo; or &ldquo;Tomorrow 09:00&rdquo;<br/>
                • <b>Grouped by provider</b>, one clear section per account<br/><br/>
                <b>Also new:</b><br/>
                • Unified Xiaomi MiMo &mdash; pay-as-you-go balance and Token Plan Credits in one account<br/>
                • Claude Code multi-account &mdash; auto-discovers every logged-in account<br/>
                • Codex &amp; Claude OAuth &mdash; usage read from your stored logins, tokens auto-refreshed<br/>
                • Nebius &amp; Xiaomi silent session refresh &mdash; stay connected without re-logging in
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
