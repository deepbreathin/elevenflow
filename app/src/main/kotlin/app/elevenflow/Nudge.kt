package app.elevenflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Heads-up notifications for when a dictation did NOT land in a text box:
 * - ready:  text is in the clipboard; tap = paste into whatever box you're in now, "Copy" = just copy.
 * - failed: nothing transcribed yet (no signal); tap = try the saved recording again.
 * - revive: Android stopped the bubble, or restarted it without mic access (it hands us pure silence);
 *           tap = (re)start it with the mic, which Android allows from a notification tap.
 * One slot for results (newest replaces older; History keeps everything).
 */
object Nudge {
    private const val CH = "results"
    private const val RESULT_ID = 2
    private const val REVIVE_ID = 3

    private fun nm(ctx: Context): NotificationManager =
        ctx.getSystemService(NotificationManager::class.java).also {
            it.createNotificationChannel(NotificationChannel(CH, "Dictation not pasted", NotificationManager.IMPORTANCE_HIGH))
        }

    private fun action(ctx: Context, what: String, id: Long, req: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, req,
            Intent(ctx, NudgeReceiver::class.java).setAction(what).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun ready(ctx: Context, id: Long, text: String) = nm(ctx).notify(RESULT_ID,
        Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Dictation ready — tap to paste")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(action(ctx, "paste", id, 1))
            .addAction(Notification.Action.Builder(null, "Copy", action(ctx, "copy", id, 2)).build())
            .setAutoCancel(true)
            .setGroup("result")
            .setTimeoutAfter(30 * 60_000L)
            .build())

    fun failed(ctx: Context, id: Long, partial: String) = nm(ctx).notify(RESULT_ID,
        Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Not transcribed yet — tap to try again")
            .setContentText(if (partial.isBlank()) "Recording is saved on the phone." else "Partial, in clipboard: $partial")
            .setContentIntent(action(ctx, "retry", id, 3))
            .setAutoCancel(true)
            .build())

    fun revive(ctx: Context, title: String = "ElevenFlow bubble is off — tap to turn it on") = nm(ctx).notify(REVIVE_ID,
        Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setGroup("revive")
            // tapping a notification is one of the few ways Android lets a mic service start in the background
            .setContentIntent(PendingIntent.getForegroundService(ctx, 4,
                Intent(ctx, FlowService::class.java).setAction("revive"), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())

    fun cancelResult(ctx: Context) = nm(ctx).cancel(RESULT_ID)
    fun cancelRevive(ctx: Context) = nm(ctx).cancel(REVIVE_ID)
}
