package io.github.movebrickschi.restfulall.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import javax.swing.JTextPane
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.BadLocationException
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * JTextPane with JSON syntax highlighting matching IntelliJ's default JSON editor look:
 *   - keys:    bold + magenta/purple
 *   - strings: green
 *   - numbers: blue (light) / teal-blue (dark)
 *   - keywords (true/false/null): bold + blue/orange
 *   - punctuation / plain text: default foreground, not bold
 *
 * Non-JSON text (error messages, streaming plain text) is rendered without
 * any color attributes, using the component's default foreground.
 */
class JsonSyntaxTextPane(editable: Boolean = false) : JTextPane() {

    companion object {
        private val KEY_ATTRS = SimpleAttributeSet().also {
            StyleConstants.setBold(it, true)
            StyleConstants.setForeground(it, JBColor(Color(0x871094), Color(0xC77DBB)))
        }
        private val STRING_ATTRS = SimpleAttributeSet().also {
            StyleConstants.setBold(it, false)
            StyleConstants.setForeground(it, JBColor(Color(0x067D17), Color(0x6A8759)))
        }
        private val NUMBER_ATTRS = SimpleAttributeSet().also {
            StyleConstants.setBold(it, false)
            StyleConstants.setForeground(it, JBColor(Color(0x1750EB), Color(0x6897BB)))
        }
        private val KEYWORD_ATTRS = SimpleAttributeSet().also {
            StyleConstants.setBold(it, true)
            StyleConstants.setForeground(it, JBColor(Color(0x0033B3), Color(0xCC7832)))
        }
        private val PLAIN_ATTRS = SimpleAttributeSet().also {
            StyleConstants.setBold(it, false)
        }

        private fun resolveEditorFont(): Font {
            return try {
                EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
                    ?: Font(Font.MONOSPACED, Font.PLAIN, 13)
            } catch (_: Throwable) {
                Font(Font.MONOSPACED, Font.PLAIN, 13)
            }
        }
    }

    private enum class TokenType { KEY, STRING_VALUE, NUMBER, KEYWORD, PUNCTUATION, PLAIN }
    private data class Token(val text: String, val type: TokenType)

    private var isHighlighting = false
    private val debounceTimer = Timer(300) { applyHighlighting() }.apply { isRepeats = false }

    init {
        isEditable = editable
        font = resolveEditorFont()

        if (editable) {
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) { if (!isHighlighting) debounceTimer.restart() }
                override fun removeUpdate(e: DocumentEvent) { if (!isHighlighting) debounceTimer.restart() }
                override fun changedUpdate(e: DocumentEvent) {}
            })
        }
    }

    /** Ensure the pane tracks the viewport width so content wraps instead of scrolling horizontally. */
    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun setText(t: String?) {
        val text = t ?: ""
        isHighlighting = true
        try {
            val doc = styledDocument
            if (doc.length > 0) doc.remove(0, doc.length)
            val trimmed = text.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                insertHighlightedJson(doc, text)
            } else {
                if (text.isNotEmpty()) doc.insertString(0, text, PLAIN_ATTRS)
            }
        } catch (_: BadLocationException) {
            try { super.setText(text) } catch (_: Exception) {}
        } finally {
            isHighlighting = false
        }
    }

    /**
     * Append plain (unstyled) text to the end of the document.
     * Used for SSE / NDJSON / WebSocket streaming where text arrives incrementally
     * and full JSON highlighting would be premature.
     */
    fun appendPlain(text: String) {
        if (text.isEmpty()) return
        isHighlighting = true
        try {
            val doc = styledDocument
            doc.insertString(doc.length, text, PLAIN_ATTRS)
        } catch (_: BadLocationException) {
        } finally {
            isHighlighting = false
        }
    }

    /**
     * Re-apply JSON syntax highlighting to the current document content.
     * Called externally (e.g. when switching body type to "json") and internally
     * by the debounce timer after the user stops typing.
     */
    fun applyHighlighting() {
        if (isHighlighting) return
        isHighlighting = true
        try {
            val doc = styledDocument
            val plain = doc.getText(0, doc.length)
            val savedCaret = caretPosition
            if (doc.length > 0) doc.remove(0, doc.length)
            val trimmed = plain.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                insertHighlightedJson(doc, plain)
            } else {
                if (plain.isNotEmpty()) doc.insertString(0, plain, PLAIN_ATTRS)
            }
            try {
                caretPosition = savedCaret.coerceIn(0, doc.length)
            } catch (_: Exception) {}
        } catch (_: Exception) {
        } finally {
            isHighlighting = false
        }
    }

    private fun insertHighlightedJson(doc: StyledDocument, json: String) {
        for (token in tokenizeJson(json)) {
            val attrs = when (token.type) {
                TokenType.KEY -> KEY_ATTRS
                TokenType.STRING_VALUE -> STRING_ATTRS
                TokenType.NUMBER -> NUMBER_ATTRS
                TokenType.KEYWORD -> KEYWORD_ATTRS
                else -> PLAIN_ATTRS
            }
            doc.insertString(doc.length, token.text, attrs)
        }
    }

    /**
     * Character-level JSON tokenizer.
     *
     * Tracks object/array context via a stack (true = object, false = array).
     * A string token is classified as KEY when it appears in object context at the
     * key position (before the ':'), and as STRING_VALUE otherwise.
     */
    private fun tokenizeJson(json: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val contextStack = ArrayDeque<Boolean>() // true = object, false = array
        var expectKey = false

        while (i < json.length) {
            val ch = json[i]
            when {
                ch == '"' -> {
                    val sb = StringBuilder("\"")
                    i++
                    var escaped = false
                    while (i < json.length) {
                        val c = json[i]
                        sb.append(c)
                        when {
                            escaped -> escaped = false
                            c == '\\' -> escaped = true
                            c == '"' -> { i++; break }
                        }
                        i++
                    }
                    val type = if (expectKey && contextStack.lastOrNull() == true) TokenType.KEY
                               else TokenType.STRING_VALUE
                    tokens.add(Token(sb.toString(), type))
                    expectKey = false
                }

                ch == '{' -> {
                    tokens.add(Token("{", TokenType.PUNCTUATION))
                    contextStack.addLast(true)
                    expectKey = true
                    i++
                }
                ch == '}' -> {
                    tokens.add(Token("}", TokenType.PUNCTUATION))
                    contextStack.removeLastOrNull()
                    expectKey = false
                    i++
                }
                ch == '[' -> {
                    tokens.add(Token("[", TokenType.PUNCTUATION))
                    contextStack.addLast(false)
                    expectKey = false
                    i++
                }
                ch == ']' -> {
                    tokens.add(Token("]", TokenType.PUNCTUATION))
                    contextStack.removeLastOrNull()
                    expectKey = false
                    i++
                }
                ch == ':' -> {
                    tokens.add(Token(":", TokenType.PUNCTUATION))
                    expectKey = false
                    i++
                }
                ch == ',' -> {
                    tokens.add(Token(",", TokenType.PUNCTUATION))
                    expectKey = contextStack.lastOrNull() == true
                    i++
                }

                ch.isWhitespace() -> {
                    val sb = StringBuilder()
                    while (i < json.length && json[i].isWhitespace()) { sb.append(json[i]); i++ }
                    tokens.add(Token(sb.toString(), TokenType.PLAIN))
                }

                ch == '-' || ch.isDigit() -> {
                    tokens.add(parseNumber(json, i).also { i = it.second }.first)
                }

                ch == 't' && json.regionMatches(i, "true", 0, 4) -> {
                    tokens.add(Token("true", TokenType.KEYWORD)); i += 4
                }
                ch == 'f' && json.regionMatches(i, "false", 0, 5) -> {
                    tokens.add(Token("false", TokenType.KEYWORD)); i += 5
                }
                ch == 'n' && json.regionMatches(i, "null", 0, 4) -> {
                    tokens.add(Token("null", TokenType.KEYWORD)); i += 4
                }

                else -> {
                    tokens.add(Token(ch.toString(), TokenType.PLAIN)); i++
                }
            }
        }
        return tokens
    }

    /** Returns (Token, newIndex) after consuming a JSON number (or lone '-' as PLAIN). */
    private fun parseNumber(json: String, start: Int): Pair<Token, Int> {
        var j = start
        val sb = StringBuilder()

        if (j < json.length && json[j] == '-') {
            val next = j + 1
            if (next >= json.length || (!json[next].isDigit() && json[next] != '.')) {
                return Token("-", TokenType.PLAIN) to (j + 1)
            }
            sb.append('-'); j++
        }

        while (j < json.length && (json[j].isDigit() || json[j] == '.')) { sb.append(json[j]); j++ }

        if (j < json.length && (json[j] == 'e' || json[j] == 'E')) {
            sb.append(json[j]); j++
            if (j < json.length && (json[j] == '+' || json[j] == '-')) { sb.append(json[j]); j++ }
            while (j < json.length && json[j].isDigit()) { sb.append(json[j]); j++ }
        }

        return Token(sb.toString(), TokenType.NUMBER) to j
    }
}
