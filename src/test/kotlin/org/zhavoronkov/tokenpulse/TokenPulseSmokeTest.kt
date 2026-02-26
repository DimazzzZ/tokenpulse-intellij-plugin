package org.zhavoronkov.tokenpulse

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.service.HttpClientService
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.ui.TokenPulseConfigurable
import org.zhavoronkov.tokenpulse.ui.TokenPulseDashboardDialog

class TokenPulseSmokeTest : BasePlatformTestCase() {

    fun testServicesAreRegistered() {
        assertNotNull(HttpClientService.getInstance())
        assertNotNull(BalanceRefreshService.getInstance())
        assertNotNull(TokenPulseSettingsService.getInstance())
    }

    fun testConfigurableCreation() {
        val configurable = TokenPulseConfigurable()
        val component = configurable.createComponent()
        assertNotNull(component)
        configurable.dispose()
    }

    fun testDashboardCreation() {
        val dialog = TokenPulseDashboardDialog(project)
        assertNotNull(dialog.contentPane)
    }
}
