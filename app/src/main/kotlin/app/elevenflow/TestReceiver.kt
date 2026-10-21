package app.elevenflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only (registered in src/debug/AndroidManifest.xml, guarded by the DUMP permission,
 * which only adb/system hold). Feeds a WAV into the bubble as if spoken:
 *   adb shell am broadcast -n app.elevenflow/.TestReceiver --es wav /data/local/tmp/x.wav
 * Also: --ei size <dp> (bubble size), --es image <path> (bubble image, as if chosen in the app).
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        // optional extras: --ei size <dp>   --es image /data/local/tmp/pic.png   (bubble image, as if picked)
        if (intent.hasExtra("size")) Settings.put(ctx, "sizeDp", intent.getIntExtra("size", 60))
        intent.getStringExtra("image")?.let { path ->
            val done = goAsync()
            Thread { BubbleArt.setFrom(ctx, android.net.Uri.fromFile(java.io.File(path))); done.finish() }.start()
        }
        intent.getStringExtra("wav")?.let {
            ctx.startService(Intent(ctx, FlowService::class.java).setAction("test").putExtra("wav", it))
        }
    }
}
