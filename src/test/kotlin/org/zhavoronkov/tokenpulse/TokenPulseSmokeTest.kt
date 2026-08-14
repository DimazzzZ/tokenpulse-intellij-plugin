package org.zhavoronkov.tokenpulse

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.service.HttpClientService
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.ui.CodexConnectDialog
import org.zhavoronkov.tokenpulse.ui.TokenPulseConfigurable
import org.zhavoronkov.tokenpulse.ui.TokenPulseDashboardDialog
import org.zhavoronkov.tokenpulse.ui.XiaomiConnectDialog

// Routed to the `platformTest` Gradle task by class name (see build.gradle.kts) —
// not by @Tag, since this is a JUnit 3-style junit.framework.TestCase run
// through the Vintage engine, which does not honor Jupiter's @Tag.
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
    }

    // DialogWrapper.contentPane/contentPanel/title all delegate to the
    // DialogWrapperPeer, which returns null under this headless platform
    // test peer even after fully successful construction — so it can't be
    // used to assert a dialog built correctly. The constructor call not
    // throwing is the real assertion: DialogWrapper.init() (invoked
    // synchronously from each dialog's own init {} block) runs
    // createCenterPanel(), and IntelliJ's UI DSL throws UiDslException the
    // moment a denied tag (<html>, <body>, empty-href) reaches comment()/
    // rowComment()/text()/contextHelp() (see DslCommentHtmlGuardTest for the
    // static-scan equivalent).
    fun testDashboardCreation() {
        TokenPulseDashboardDialog(project)
    }

    fun testCodexConnectDialogCreation() {
        CodexConnectDialog()
    }

    fun testXiaomiConnectDialogCreation() {
        XiaomiConnectDialog()
    }
}
