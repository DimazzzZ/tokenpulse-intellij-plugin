package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DslCommentTextTest {

    @Test
    fun `strips a matched html pair`() {
        assertEquals("hello world", DslCommentText.sanitize("<html>hello world</html>"))
    }

    @Test
    fun `strips an unpaired opening html tag`() {
        assertEquals("hello world", DslCommentText.sanitize("<html>hello world"))
    }

    @Test
    fun `strips an attributed html tag`() {
        assertEquals("hello", DslCommentText.sanitize("<html lang=\"en\">hello</html>"))
    }

    @Test
    fun `strips body tags`() {
        assertEquals("hello", DslCommentText.sanitize("<html><body>hello</body></html>"))
    }

    @Test
    fun `is case insensitive`() {
        assertEquals("hello", DslCommentText.sanitize("<HTML><Body>hello</BODY></html>"))
    }

    @Test
    fun `preserves inner tags`() {
        assertEquals(
            "line one<br>line <b>two</b>",
            DslCommentText.sanitize("<html>line one<br>line <b>two</b></html>")
        )
    }

    @Test
    fun `leaves plain text unchanged`() {
        assertEquals("just plain text", DslCommentText.sanitize("just plain text"))
    }
}
