package app.elevenflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only (registered in src/debug/AndroidManifest.xml, guarded by the DUMP permission,
 * which only adb/system hold). Feeds a WAV into the bubble as if spoken:
 *   adb shell am broadcast -n app.elevenflow/.TestReceiver --es wav /data/local/tmp/x.wav
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        ctx.startService(Intent(ctx, FlowService::class.java).setAction("test").putExtra("wav", intent.getStringExtra("wav")))
    }
}
