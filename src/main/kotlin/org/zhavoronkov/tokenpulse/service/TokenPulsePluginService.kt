package org.zhavoronkov.tokenpulse.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.util.Properties

@Service(Service.Level.APP)
class TokenPulsePluginService {
    val version: String = loadVersion()

    private fun loadVersion(): String {
        return try {
            val props = Properties()
            props.load(javaClass.classLoader.getResourceAsStream("tokenpulse.properties"))
            props.getProperty("version", "unknown")
        } catch (_: Exception) {
            "unknown"
        }
    }

    companion object {
        fun getVersion(): String = service<TokenPulsePluginService>().version
    }
}
