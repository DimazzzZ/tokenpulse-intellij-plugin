package org.zhavoronkov.tokenpulse.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import org.zhavoronkov.tokenpulse.utils.PluginVersion

@Service(Service.Level.APP)
class TokenPulsePluginService {
    val version: String = PluginVersion.value

    companion object {
        fun getVersion(): String = service<TokenPulsePluginService>().version
    }
}
