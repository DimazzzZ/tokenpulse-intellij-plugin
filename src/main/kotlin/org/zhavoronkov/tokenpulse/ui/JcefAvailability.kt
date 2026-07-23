package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

/**
 * Detects whether JCEF (embedded Chromium) is usable on this IDE build WITHOUT
 * dragging JCEF types into the class's field/imports scope.
 *
 * The plugin does not depend on the JCEF module. On IDE builds where the JCEF
 * classes are absent (observed on IntelliJ IDEA 2026.2 with a mismatched JBR),
 * any class that has JCEF-typed FIELDS or eager references fails to link with
 * NoClassDefFoundError at the call site — before its method bodies can run.
 * That is exactly why guarding [XiaomiJcefLoginDialog.isSupported] didn't work:
 * that class has a `browser: JBCefBrowser` field, so loading it to call the
 * companion method already throws.
 *
 * This object holds NO JCEF-typed members, so it loads fine everywhere. The
 * fully-qualified call to `JBCefApp.isSupported()` is an `invokestatic`
 * symbolic reference resolved lazily on execution — a `catch (Throwable)`
 * around it CAN catch the NoClassDefFoundError / ClassNotFoundException it
 * raises on JCEF-less builds.
 */
object JcefAvailability {
    fun isAvailable(): Boolean =
        try {
            // Class.forName is a belt-and-suspenders explicit probe; the real
            // decision comes from JBCefApp.isSupported() (which also handles
            // JBR-mismatched builds where the class loads but JCEF is disabled).
            Class.forName("com.intellij.ui.jcef.JBCefApp")
            com.intellij.ui.jcef.JBCefApp.isSupported()
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            TokenPulseLogger.Provider.debug(
                "JCEF unavailable (${t.javaClass.simpleName}: ${t.message})"
            )
            false
        }
}
