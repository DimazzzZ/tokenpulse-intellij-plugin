package org.zhavoronkov.tokenpulse.ui

/**
 * Sanitizes text for IntelliJ UI DSL calls that wrap their argument in
 * `<html>...</html>` themselves — `comment()`, `rowComment()`, `text()`,
 * `contextHelp()` — and reject an explicit `<html>` or `<body>` tag from the
 * caller at runtime (`DslLabelKt.DENIED_TAGS`): a logged error in
 * production, a thrown `UiDslException` under tests.
 *
 * Removes every `<html>`/`<body>` tag — opening or closing, with or without
 * attributes, paired or unpaired — case-insensitively. All other tags
 * (`<br>`, `<b>`, `<code>`, `<a href="...">`, ...) are legal inside these
 * calls and pass through unchanged.
 */
internal object DslCommentText {

    private val htmlOrBodyTag = Regex("</?(html|body)\\b[^>]*>", RegexOption.IGNORE_CASE)

    fun sanitize(text: String): String = htmlOrBodyTag.replace(text, "").trim()
}
