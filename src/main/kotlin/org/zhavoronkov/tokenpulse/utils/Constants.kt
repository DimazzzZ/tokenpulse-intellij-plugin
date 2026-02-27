package org.zhavoronkov.tokenpulse.utils

/**
 * Application-wide constants to avoid magic numbers throughout the codebase.
 */
object Constants {
    // UI Dimensions
    const val PASSWORD_FIELD_COLUMNS = 50
    const val TEXT_AREA_COLUMNS = 36
    const val TEXT_AREA_ROWS = 12
    const val SCRIPT_PANEL_WIDTH = 560
    const val SCRIPT_PANEL_HEIGHT = 180
    const val FONT_SIZE_SMALL = 11
    const val FONT_SIZE_MEDIUM = 12
    const val FONT_SIZE_LARGE = 14

    // Text Formatting
    const val PREVIEW_TRUNCATE_LENGTH = 6
    const val PREVIEW_SUFFIX_LENGTH = 4

    // HTTP Configuration
    const val HTTP_TIMEOUT_SECONDS = 30
    const val HTTP_TIMEOUT_MS = 30000
    const val MAX_RETRY_ATTEMPTS = 3
    const val RETRY_DELAY_MS = 1000

    // API Configuration
    const val DEFAULT_PAGE_SIZE = 100
    const val MAX_PAGE_SIZE = 1000
    const val PAGINATION_LIMIT = 100

    // Balance Display
    const val CURRENCY_SCALE = 2
    const val ROUNDMODE_HALF_UP = 2

    // Dashboard
    const val DASHBOARD_COLUMNS = 4
    const val DASHBOARD_ROW_HEIGHT = 25

    // Refresh Settings
    const val DEFAULT_REFRESH_INTERVAL_MS = 60000L
    const val MIN_REFRESH_INTERVAL_MS = 10000L
    const val MAX_REFRESH_INTERVAL_MS = 300000L

    // Validation
    const val MIN_API_KEY_LENGTH = 20
    const val MAX_API_KEY_LENGTH = 200

    // Nebius Session
    const val NEBIUS_APP_SESSION_PREFIX = "__Host-app_session"
    const val NEBIUS_CSRF_TOKEN_HEADER = "X-CSRF-Token"
    const val NEBIUS_CSRF_COOKIE_HEADER = "__Host-psifi.x-csrf-token"

    // OpenAI
    const val OPENAI_API_KEY_PREFIX = "sk-"
    const val OPENAI_BEARER_PREFIX = "Bearer "

    // Cline
    const val CLINE_API_KEY_PREFIX = "clnt-"

    // OpenRouter
    const val OPENROUTER_PROVISIONING_KEY_PREFIX = "or-"
}
