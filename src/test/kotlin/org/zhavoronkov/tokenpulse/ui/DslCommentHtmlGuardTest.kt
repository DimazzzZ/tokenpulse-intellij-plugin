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

    /** One guarded call found in a source file: its 1-based line and the raw text between its parens. */
    private data class CallArgument(val line: Int, val text: String)

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_'

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
            .filter { deniedTagPattern.containsMatchIn(it.text) }
            .map { "${file.path}:${it.line}" }
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

    /** Finds every call to a name in [functionNames]. */
    private fun findCallArgumentSpans(source: String, functionNames: Set<String>): List<CallArgument> {
        val results = mutableListOf<CallArgument>()
        val n = source.length

        // Single `i = ...` per iteration (no `continue`s) so the scan stays
        // easy to follow and each branch's cursor advance is explicit.
        var i = 0
        while (i < n) {
            val skipTo = skipStringOrComment(source, i)
            val startsIdent = isIdentChar(source[i]) && (i == 0 || !isIdentChar(source[i - 1]))
            i = when {
                skipTo != null -> skipTo
                startsIdent -> appendCallAt(source, i, functionNames, into = results)
                else -> i + 1
            }
        }
        return results
    }

    /**
     * Reads the identifier starting at [start]. When it names one of
     * [functionNames] and is followed by a call, appends that call to [into].
     * Returns the index to resume scanning from either way.
     */
    private fun appendCallAt(
        source: String,
        start: Int,
        functionNames: Set<String>,
        into: MutableList<CallArgument>,
    ): Int {
        val n = source.length

        var end = start
        while (end < n && isIdentChar(source[end])) end++
        val ident = source.substring(start, end)

        var paren = end
        while (paren < n && source[paren] == ' ') paren++
        if (ident !in functionNames || paren >= n || source[paren] != '(') return end

        val closeIdx = findMatchingClose(source, paren)
        val arg = source.substring(paren + 1, closeIdx.coerceIn(paren + 1, n))
        val line = 1 + source.substring(0, start).count { it == '\n' }
        into.add(CallArgument(line, arg))
        return closeIdx + 1
    }
}
