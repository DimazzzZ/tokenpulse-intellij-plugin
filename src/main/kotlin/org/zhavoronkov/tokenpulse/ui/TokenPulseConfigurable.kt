package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import javax.swing.JComponent

class TokenPulseConfigurable : Configurable {
    private val settings = TokenPulseSettingsService.getInstance().state

    override fun createComponent(): JComponent {
        return panel {
            row("Refresh interval (minutes):") {
                intTextField(1..1440)
                    .bindIntText(settings::refreshIntervalMinutes)
            }
            group("Accounts") {
                row {
                    label("Account management will be implemented here (Table + Add/Edit dialogs)")
                }
            }
        }
    }

    override fun isModified(): Boolean = true // Simplified for now

    override fun apply() {
        // Apply settings
    }

    override fun getDisplayName(): String = "TokenPulse"
}
