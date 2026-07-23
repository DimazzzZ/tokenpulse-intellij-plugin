package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class JcefAvailabilityTest {

    /**
     * Regression: on IDE builds where the JCEF module isn't on the plugin
     * classpath, referencing com.intellij.ui.jcef.JBCefApp throws
     * NoClassDefFoundError / ClassNotFoundException (Throwables, not Exceptions).
     * [JcefAvailability.isAvailable] must swallow that and return a Boolean so the
     * Xiaomi connect dialog falls back to manual cURL capture instead of crashing.
     */
    @Test
    fun `isAvailable never throws`() {
        assertDoesNotThrow { JcefAvailability.isAvailable() }
    }
}
