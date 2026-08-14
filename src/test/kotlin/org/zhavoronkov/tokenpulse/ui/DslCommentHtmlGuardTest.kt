package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against passing a literal `<html>`/`<body>` tag into an IntelliJ UI
 * DSL call that wraps its argument in `<html>...</html>` itself and rejects
 * an explicit one at runtime — logging an error (production) or throwing
 * `UiDslException` (tests). Verified against the platform's own
 * `DslLabel`/`DslLabelKt.DENIED_TAGS` check, which guards `comment()`,
 * `rowComment()`, `text()`, and `contextHelp()`.
 *
 * `label(...)` and plain Swing `JBLabel(...)`/`.text = ...` are NOT checked
 * by the platform and must keep their `<html>` wrapper — this test must
 * never flag them.
 */
class DslCommentHtmlGuardTest {

    private val guardedFunctions = setOf("comment", "rowComment", "text", "contextHelp")
    private val deniedTagPattern = Regex("<(html|body)\\b", RegexOption.IGNORE_CASE)

    @Test
    fun `no literal html or body tag reaches a UI DSL comment-like call`() {
        val root = File("src/main/kotlin")
        check(root.isDirectory) { "expected $root to exist relative to the module working directory" }

        val violations = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> findViolations(file).asSequence() }
            .toList()

        assertTrue(
            violations.isEmpty(),
            "Literal <html>/<body> passed into a UI DSL call that rejects it at runtime:\n" +
                violations.joinToString("\n")
        )
    }

    private fun findViolations(file: File): List<String> {
        val source = file.readText()
        return findCallArgumentSpans(source, guardedFunctions)
            .filter { (_, arg) -> deniedTagPattern.containsMatchIn(arg) }
            .map { (line, _) -> "${file.path}:$line" }
    }

    /**
     * If [source] at [start] begins a string literal or a comment, returns
     * the index just past its end; otherwise null. Shared by the top-level
     * scan and by [findMatchingClose] so both agree on what counts as code.
     */
    private fun skipStringOrComment(source: String, start: Int): Int? {
        val n = source.length
        return when {
            source.startsWith("\"\"\"", start) -> {
                var i = start + 3
                while (i < n && !source.startsWith("\"\"\"", i)) i++
                (i + 3).coerceAtMost(n)
            }
            source[start] == '"' -> {
                var i = start + 1
                while (i < n && source[i] != '"') i += if (source[i] == '\\') 2 else 1
                (i + 1).coerceAtMost(n)
            }
            source[start] == '\'' -> {
                var i = start + 1
                while (i < n && source[i] != '\'') i += if (source[i] == '\\') 2 else 1
                (i + 1).coerceAtMost(n)
            }
            source.startsWith("//", start) -> {
                var i = start
                while (i < n && source[i] != '\n') i++
                i
            }
            source.startsWith("/*", start) -> {
                var i = start + 2
                while (i < n && !source.startsWith("*/", i)) i++
                (i + 2).coerceAtMost(n)
            }
            else -> null
        }
    }

    /** Index of the `)` matching the `(` at [openParenIndex], skipping nested calls/strings/comments. */
    private fun findMatchingClose(source: String, openParenIndex: Int): Int {
        val n = source.length
        var depth = 1
        var i = openParenIndex + 1
        while (i < n && depth > 0) {
            val skipTo = skipStringOrComment(source, i)
            if (skipTo != null) {
                i = skipTo
                continue
            }
            when (source[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return i - 1
    }

    /** Finds every call to a name in [functionNames], returning (1-based line, raw text between its parens). */
    private fun findCallArgumentSpans(source: String, functionNames: Set<String>): List<Pair<Int, String>> {
        val results = mutableListOf<Pair<Int, String>>()
        val n = source.length
        fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_'

        var i = 0
        while (i < n) {
            val skipTo = skipStringOrComment(source, i)
            if (skipTo != null) {
                i = skipTo
                continue
            }
            val c = source[i]
            if (isIdentChar(c) && (i == 0 || !isIdentChar(source[i - 1]))) {
                var j = i
                while (j < n && isIdentChar(source[j])) j++
                val ident = source.substring(i, j)
                var k = j
                while (k < n && source[k] == ' ') k++
                if (ident in functionNames && k < n && source[k] == '(') {
                    val closeIdx = findMatchingClose(source, k)
                    val arg = source.substring(k + 1, closeIdx.coerceIn(k + 1, n))
                    val line = 1 + source.substring(0, i).count { it == '\n' }
                    results.add(line to arg)
                    i = closeIdx + 1
                    continue
                }
                i = j
                continue
            }
            i++
        }
        return results
    }
}
