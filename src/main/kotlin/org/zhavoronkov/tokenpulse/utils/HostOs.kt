package org.zhavoronkov.tokenpulse.utils

/** Host operating system, used to branch platform-specific behavior. */
enum class HostOs { WINDOWS, MACOS, LINUX, UNKNOWN }

/** Detect the current host OS from the `os.name` system property. */
fun detectHostOs(): HostOs {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("windows") -> HostOs.WINDOWS
        osName.contains("mac") || osName.contains("darwin") -> HostOs.MACOS
        osName.contains("linux") || osName.contains("unix") -> HostOs.LINUX
        else -> HostOs.UNKNOWN
    }
}
