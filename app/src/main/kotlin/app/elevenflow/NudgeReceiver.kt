package app.elevenflow

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File

/** Handles taps on the "not pasted" notifications. */
class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0)
        val entry = History.all(ctx).firstOrNull { it.id == id } ?: return
        when (intent.action) {
            "copy" -> { copy(ctx, entry.text); Nudge.cancelResult(ctx); toast(ctx, "Copied") }
            "paste" -> { copy(ctx, entry.text); Nudge.cancelResult(ctx); pasteAfterShadeCloses(ctx, entry.text) }
            "retry" -> retry(ctx, entry)
        }
    }

    private fun pasteAfterShadeCloses(ctx: Context, text: String) {
        val a11y = InsertService.instance ?: return toast(ctx, "Copied — long-press a text box to paste")
        if (Build.VERSION.SDK_INT >= 31) a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        Handler(Looper.getMainLooper()).postDelayed({
            if (a11y.insert(text) != InsertService.Result.OK) toast(ctx, "Copied — tap a text box, then long-press to paste")
        }, 500)
    }

    private fun retry(ctx: Context, e: History.Entry) {
        val audio = e.audio?.let(::File)?.takeIf { it.exists() } ?: return toast(ctx, "Recording no longer on the phone")
        toast(ctx, "Trying again…")
        val done = goAsync()
        Thread {
            val r = runCatching { Batch.transcribe(Settings.apiKey(ctx), audio, Settings.keyterms(ctx), Settings.autoLanguage(ctx)) }
            Handler(Looper.getMainLooper()).post {
                r.onSuccess { t ->
                    History.update(ctx, e.id, t, ok = true)
                    if (t.isBlank()) { Nudge.cancelResult(ctx); toast(ctx, "Heard nothing in that recording") }
                    else { copy(ctx, t); Nudge.ready(ctx, e.id, t) }
                }.onFailure { Nudge.failed(ctx, e.id, e.text); toast(ctx, "Still no luck: ${it.message}") }
                done.finish()
            }
        }.start()
    }

    private fun copy(ctx: Context, t: String) =
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dictation", t))

    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
}
