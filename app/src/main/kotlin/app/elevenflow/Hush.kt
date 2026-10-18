package app.elevenflow

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Quiet while dictating: music is paused for the recording and comes back afterwards.
 *
 * Focus is only taken when something is actually playing: Spotify keeps the focus for a while after
 * you pause it, and handing focus back would make it start playing. If another app took the focus
 * meanwhile, Android tells Spotify it lost it for good and it stays stopped, so when music was
 * playing and isn't back a moment after we let go, this presses "play" for the music app.
 */
object Hush {
    private val main = Handler(Looper.getMainLooper())
    private var held = false
    private var musicWas = false

    private val request by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ }, main)
            .build()
    }

    // For a normal app this list holds only players that are playing right now.
    private fun playing(am: AudioManager, vararg usages: Int) =
        am.activePlaybackConfigurations.any { it.audioAttributes.usage in usages }

    /** Recording starts. */
    fun begin(ctx: Context) = main.post {
        val am = ctx.getSystemService(AudioManager::class.java)
        main.removeCallbacksAndMessages(RESUME)
        musicWas = playing(am, AudioAttributes.USAGE_MEDIA)
        if (musicWas && !held) held = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Recording stopped (sent or cancelled). */
    fun end(ctx: Context) = main.post {
        if (!held) return@post
        val am = ctx.getSystemService(AudioManager::class.java)
        am.abandonAudioFocusRequest(request)
        held = false
        if (!musicWas) return@post
        main.postAtTime({
            if (!playing(am, AudioAttributes.USAGE_MEDIA)) {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            }
        }, RESUME, android.os.SystemClock.uptimeMillis() + 1_200)
    }

    private val RESUME = Any()
}
