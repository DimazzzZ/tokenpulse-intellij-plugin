package org.zhavoronkov.tokenpulse.startup

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
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

        val pluginId = PluginId.getId("org.zhavoronkov.tokenpulse")
        val pluginDescriptor = PluginManagerCore.getPlugin(pluginId)
        val currentVersion = pluginDescriptor?.version ?: ""

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
                <b>Thank you for sticking with TokenPulse!</b><br/>
                I really appreciate everyone who waited for fixes after 0.1.0.<br/><br/>
                <b>New in v$version:</b><br/>
                • ChatGPT now uses Codex CLI for simpler setup (no OAuth)<br/>
                • Improved credential handling and reduced notification spam<br/>
                • Better Nebius balance extraction and connection reliability<br/>
                • Code quality improvements and bug fixes
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
