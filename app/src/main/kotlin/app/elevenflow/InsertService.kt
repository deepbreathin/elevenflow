package app.elevenflow

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo.*

/**
 * Puts text into the focused field of whatever app is in front. The caller has already put the text
 * on the clipboard, so this pastes it; if the app ignores paste, it sets the field's text directly.
 */
class InsertService : AccessibilityService() {

    enum class Result { OK, NO_FIELD, REFUSED }

    companion object {
        @Volatile var instance: InsertService? = null
    }

    override fun onServiceConnected() {
        instance = this; keyboardUp = null
        // Android binds this at boot and whenever our process restarts: bring the bubble back if it should be on.
        if (Settings.wantOn(this) && !FlowService.running) {
            try { startForegroundService(Intent(this, FlowService::class.java)) } catch (e: Exception) { Nudge.revive(this) }
        }
    }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    private var keyboardUp: Boolean? = null

    /** Tell the bubble whenever a keyboard window appears or goes away. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val up = try { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } } catch (e: Exception) { return }
        if (up != keyboardUp) { keyboardUp = up; FlowService.instance?.onKeyboard(up) }
    }
    override fun onInterrupt() {}

    /** Input focus, or failing that any focused editable node in any window (some apps report focus oddly). */
    private fun focusedField(): AccessibilityNodeInfo? {
        findFocus(FOCUS_INPUT)?.let { if (it.isEditable) return it }
        for (w in windows) {
            val found = w.root?.let { search(it) }
            if (found != null) return found
        }
        return null
    }

    private fun search(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable && n.isFocused) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> search(c)?.let { return it } }
        return null
    }

    /**
     * WhatsApp/Telegram report their grey placeholder ("Message") as the field's text and don't set
     * isShowingHintText, so an empty box looks like it already says "Message". Treat that as empty.
     */
    private fun realText(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString() ?: return ""
        if (node.isShowingHintText) return ""
        val hint = node.hintText?.toString()
        if (hint != null && t == hint) return ""
        val atStart = node.textSelectionStart <= 0 && node.textSelectionEnd <= 0
        if (atStart && t.trim().trimEnd('…', '.').lowercase() in COMMON_HINTS) return ""
        return t
    }

    fun insert(raw: String): Result {
        val node = focusedField() ?: return Result.NO_FIELD
        val existing = realText(node)
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > existing.length || e > existing.length) { s = existing.length; e = s }
        val from = minOf(s, e); val to = maxOf(s, e)
        val before = existing.substring(0, from)
        val text = if (before.isNotEmpty() && !before.last().isWhitespace()) " $raw" else raw

        // Paste first: the app inserts at its own cursor, so it can't be fooled by placeholder text.
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        if (text != raw) cm.setPrimaryClip(android.content.ClipData.newPlainText("dictation", text))
        val pasted = node.performAction(ACTION_PASTE)
        if (text != raw) cm.setPrimaryClip(android.content.ClipData.newPlainText("dictation", raw))   // clipboard keeps the clean text
        if (pasted) return Result.OK

        val args = Bundle().apply {
            putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + text + existing.substring(to))
        }
        if (!node.performAction(ACTION_SET_TEXT, args)) return Result.REFUSED
        val pos = from + text.length
        node.performAction(ACTION_SET_SELECTION, Bundle().apply {
            putInt(ACTION_ARGUMENT_SELECTION_START_INT, pos)
            putInt(ACTION_ARGUMENT_SELECTION_END_INT, pos)
        })
        return Result.OK
    }
}

private val COMMON_HINTS = setOf(
    "message", "type a message", "write a message", "send a message", "text message",
    "reply", "write a reply", "add a comment", "search",
)
