package app.elevenflow

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.hypot

/**
 * Floating mic bubble. Recording starts on touch-down. Quick tap = stays on until the next tap;
 * hold longer than the hold setting = push-to-talk, release sends.
 * Drag to move. Last stop→text latency shows in the notification.
 */
class FlowService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var instance: FlowService? = null
        @Volatile var keyboardUp = false
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 / 20   // 50 ms of 16-bit mono
        private const val COMMIT_TIMEOUT_MS = 4000L
    }

    private enum class State { IDLE, RECORDING, WAITING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var bubble: Bubble
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var meter: Meter
    private lateinit var meterLp: WindowManager.LayoutParams
    private var session: Session? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        // Debug-only test hook: feed a 16 kHz mono WAV as if it were the mic (real time), then stop.
        //   adb shell am broadcast -n app.elevenflow/.TestReceiver --es wav /data/local/tmp/x.wav
        if (BuildConfig.DEBUG && intent?.action == "test" && running) {
            intent.getStringExtra("wav")?.let { path -> main.post { if (session == null) { session = Session(java.io.File(path)).also { it.start() }; render() } } }
            return START_STICKY
        }
        if (running) {
            // tapped the "mic blocked" notification: re-claim the mic now that Android allows it
            if (intent?.action == "revive") try {
                startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); Nudge.cancelRevive(this)
            } catch (_: Exception) {}
            return START_STICKY
        }
        // Android refuses mic foreground services started from the background (e.g. its own restart after
        // killing us). Rather than crash, ask for one tap on a notification, which Android does allow.
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Nudge.revive(this); stopSelf(); return START_NOT_STICKY
        }
        Nudge.cancelRevive(this)
        running = true
        instance = this
        getSharedPreferences("flow", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        addBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        getSharedPreferences("flow", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        session?.cancel()
        if (::bubble.isInitialized) wm.removeView(bubble)
        if (::meter.isInitialized) wm.removeView(meter)
        super.onDestroy()
    }

    /** Always-on notification. Tap opens the app (stopping is only from the app's button). */
    private fun notification(detail: String = "Ready"): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("flow", "Bubble", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "flow")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ElevenFlow bubble is on")
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setGroup("bubble")
            .build()
    }

    // ---------- bubble ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        wm = getSystemService(WindowManager::class.java)
        bubble = Bubble(this)
        lp = WindowManager.LayoutParams(
            dp(Settings.sizeDp(this)), dp(Settings.sizeDp(this)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // never take focus, so the text field you are dictating into keeps it
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        restorePosition()

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var downAt = 0L
        var dragging = false; var startedHere = false
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; downAt = SystemClock.uptimeMillis()
                    dragging = false; startedHere = false
                    if (session == null) { startSession(); startedHere = true; v.buzz() }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > dp(24)) {
                        dragging = true
                    }
                    if (dragging) {
                        lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                        wm.updateViewLayout(bubble, lp)
                        placeMeter()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) savePosition()
                    val s = session
                    val held = SystemClock.uptimeMillis() - downAt
                    when {
                        dragging && !startedHere -> {}                               // just moved it; keep going
                        startedHere && (dragging || held > Settings.holdMs(this)) -> { s?.finish(); v.buzz() }   // push-to-talk release
                        startedHere -> {}                                            // quick tap: keep listening
                        s?.state == State.RECORDING -> { s.finish(); v.buzz() }      // second tap: stop
                    }
                }
                MotionEvent.ACTION_CANCEL -> if (startedHere) { session?.finish() }
            }
            true
        }
        wm.addView(bubble, lp)
        meter = Meter(this)
        meterLp = WindowManager.LayoutParams(
            dp(104), dp(34),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        meter.visibility = View.GONE
        wm.addView(meter, meterLp)
        applyVisibility()
    }

    // ---------- position that survives fold/unfold ----------

    private fun screen(): android.graphics.Rect =
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds
        else android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

    /**
     * Stored relative to the screen, not in pixels: near a side edge it keeps its distance from that edge,
     * otherwise it keeps the same fraction across; vertically it's always a fraction. So unfolding keeps a
     * right-edge bubble on the right edge of the big screen.
     */
    private fun savePosition() {
        val b = screen(); val size = lp.width
        val d = resources.displayMetrics.density
        getSharedPreferences("flow", MODE_PRIVATE).edit()
            .putFloat("posFx", (lp.x + size / 2f) / b.width())
            .putFloat("posFy", (lp.y + size / 2f) / b.height())
            .putFloat("posLeftDp", lp.x / d)
            .putFloat("posRightDp", (b.width() - lp.x - size) / d)
            .apply()
    }

    private fun restorePosition() {
        val b = screen(); val size = lp.width
        val prefs = getSharedPreferences("flow", MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val fx = prefs.getFloat("posFx", 0.9f)
        val fy = prefs.getFloat("posFy", 0.5f)
        lp.x = when {
            fx < 0.25f -> (prefs.getFloat("posLeftDp", 16f) * d).toInt()
            fx > 0.75f -> b.width() - size - (prefs.getFloat("posRightDp", 16f) * d).toInt()
            else -> (fx * b.width() - size / 2f).toInt()
        }.coerceIn(0, maxOf(0, b.width() - size))
        lp.y = (fy * b.height() - size / 2f).toInt().coerceIn(0, maxOf(0, b.height() - size))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::bubble.isInitialized) return
        // window metrics settle a beat after the fold event
        main.postDelayed({
            restorePosition()
            wm.updateViewLayout(bubble, lp)
            placeMeter()
        }, 150)
    }

    /** Level meter sits beside the bubble, on whichever side has more screen. */
    private fun placeMeter() {
        if (!::meter.isInitialized) return
        val onRight = lp.x + lp.width / 2 > resources.displayMetrics.widthPixels / 2
        meterLp.x = if (onRight) lp.x - meterLp.width - dp(4) else lp.x + lp.width + dp(4)
        meterLp.y = lp.y + (lp.height - meterLp.height) / 2
        wm.updateViewLayout(meter, meterLp)
    }

    // held as a field: SharedPreferences only keeps weak references to listeners
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "sizeDp") main.post {
            lp.width = dp(Settings.sizeDp(this)); lp.height = lp.width
            wm.updateViewLayout(bubble, lp)
            placeMeter()
        }
        if (key == "kbonly" || key == "meter") onKeyboard(keyboardUp)
        if (key == "idleOpacity" || key == "recOpacity") render()
    }

    fun onKeyboard(up: Boolean) { keyboardUp = up; main.post { applyVisibility() } }

    /** Keyboard-only mode hides the bubble unless a keyboard is up or a dictation is still in flight. */
    private fun applyVisibility() {
        if (!::bubble.isInitialized) return
        val show = !Settings.keyboardOnly(this) || InsertService.instance == null || keyboardUp || session != null
        val flags = if (show) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        bubble.visibility = if (show) View.VISIBLE else View.GONE
        if (flags != lp.flags) { lp.flags = flags; wm.updateViewLayout(bubble, lp) }
        val rec = session?.state == State.RECORDING && Settings.meter(this)
        if (rec && meter.visibility != View.VISIBLE) { meter.clear(); placeMeter() }
        meter.visibility = if (rec) View.VISIBLE else View.GONE
    }

    private fun View.buzz() { if (Settings.vibrate(context)) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    private fun render() = main.post { bubble.invalidate(); applyVisibility() }

    /** Robot art (art/robot.webp): off = as drawn, on (recording) = whole thing tinted purple. */
    private inner class Bubble(ctx: Context) : View(ctx) {
        private val off = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.bubble_off)
        private val on = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.bubble_on)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dst = android.graphics.RectF()

        override fun onDraw(c: Canvas) {
            val rec = session?.state == State.RECORDING
            p.alpha = (if (rec) Settings.recOpacity(context) else Settings.idleOpacity(context)) * 255 / 100
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            c.drawBitmap(if (rec) on else off, null, dst, p)
        }
    }

    /** Scrolling bars of recent mic level: newest on the right, only shown while recording. */
    private inner class Meter(ctx: Context) : View(ctx) {
        private val levels = FloatArray(18)
        private var head = 0
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B38B5CF6") }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#402A0B5E") }

        fun clear() { levels.fill(0f); head = 0; invalidate() }
        fun push(v: Float) { levels[head] = v; head = (head + 1) % levels.size; invalidate() }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat(); val w = width.toFloat()
            c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, pill)
            val n = levels.size
            val slot = (w - h * 0.6f) / n
            val bw = slot * 0.55f
            for (i in 0 until n) {
                val v = levels[(head + i) % n]
                val bh = maxOf(bw, v * (h - dp(10)))
                val cx = h * 0.3f + slot * (i + 0.5f)
                c.drawRoundRect(cx - bw / 2, (h - bh) / 2, cx + bw / 2, (h + bh) / 2, bw / 2, bw / 2, bar)
            }
        }
    }

    // ---------- one utterance ----------

    private fun startSession() {
        session = Session(null).also { it.start() }
        render()
    }

    /**
     * One utterance.
     * - Live: the realtime socket commits long speech in ~38 s segments on its own; every segment is kept
     *   and joined, so long recordings come back whole (not just the last segment).
     * - Always: the recording is saved as a WAV. If the live socket fails at any point, or the final
     *   commit doesn't arrive within COMMIT_TIMEOUT_MS, the WAV is sent to the whole-file endpoint instead.
     * - If that fails too (no signal), it's kept in History as "not transcribed" to retry from the app;
     *   any partial text goes to the clipboard meanwhile.
     */
    private inner class Session(private val testWav: java.io.File?) {
        @Volatile var state = State.RECORDING
        @Volatile private var recording = true
        @Volatile private var cancelled = false
        private val id = System.currentTimeMillis()
        private val wav = Recordings.newFile(this@FlowService, id)
        private val wavReady = java.util.concurrent.CountDownLatch(1)
        private val audio = java.io.ByteArrayOutputStream()
        private val scribe = Scribe(listener, Settings.apiKey(this@FlowService), keyterms(), Settings.autoLanguage(this@FlowService))
        @Volatile private var liveDead = false
        private val segments = mutableListOf<String>()
        private var lastPartial = ""
        private var releasedAt = 0L
        private var done = false

        @SuppressLint("MissingPermission")
        fun start() = thread(name = "mic") {
            val test = testWav?.inputStream()?.buffered()?.also { it.skip(44) }
            val rec = if (test != null) null else {
                val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK_BYTES * 8))
            }
            rec?.startRecording()
            val buf = ByteArray(CHUNK_BYTES)
            var testEnded = false
            var heardAnything = false   // a real mic always has some noise; exact zeros = Android blocked us
            while (true) {
                val n = if (rec != null) rec.read(buf, 0, buf.size) else {
                    Thread.sleep(50)
                    var r = 0; while (r < buf.size) { val k = test!!.read(buf, r, buf.size - r); if (k < 0) break; r += k }
                    if (r < buf.size && !testEnded) { testEnded = true; main.post { finish() } }
                    if (r <= 0) { buf.fill(0); buf.size } else r
                }
                val last = !recording
                val chunk = buf.copyOf(maxOf(n, 0))
                if (!heardAnything && chunk.any { it != 0.toByte() }) heardAnything = true
                val lvl = level(chunk)
                main.post { if (state == State.RECORDING) meter.push(lvl) }
                synchronized(audio) { audio.write(chunk) }
                if (!liveDead && !scribe.closed) scribe.send(chunk, commit = last)
                if (last) break
            }
            rec?.stop(); rec?.release(); test?.close()
            if (rec != null && !heardAnything && audio.size() > SAMPLE_RATE) {   // >0.5 s of pure silence
                main.post { micBlocked() }
                wavReady.countDown(); return@thread
            }
            if (cancelled) { wavReady.countDown(); return@thread }
            try { Recordings.writeWav(wav, synchronized(audio) { audio.toByteArray() }, SAMPLE_RATE) } catch (_: Exception) {}
            wavReady.countDown()
        }

        fun finish() {
            if (state != State.RECORDING) return
            releasedAt = SystemClock.uptimeMillis()
            state = State.WAITING
            recording = false
            render()
            if (liveDead) viaFile()
            else main.postDelayed({ if (!done && !liveDead) liveFailed() }, COMMIT_TIMEOUT_MS)
        }

        /** Android fed us silence (bubble restarted in the background without mic rights). */
        private fun micBlocked() {
            cancel()
            Nudge.revive(this@FlowService, "Android blocked the mic — tap here, then dictate again")
            end("Mic blocked — tap the alert")
        }

        fun cancel() {
            cancelled = true
            recording = false
            done = true
            scribe.close()
            thread { wavReady.await(); wav.delete() }
        }

        private val listener get() = object : Scribe.Listener {
            override fun onPartial(text: String) { lastPartial = text }
            override fun onCommitted(text: String) {
                main.post {
                    if (done || liveDead) return@post
                    if (text.isNotBlank()) segments += text.trim()
                    lastPartial = ""
                    if (state == State.WAITING) deliver(joined())
                }
            }
            override fun onError(msg: String) {
                main.post {
                    if (done || liveDead) return@post
                    if (msg.startsWith("insufficient_audio_activity") && state == State.WAITING && segments.isEmpty()) finishSilently()
                    else liveFailed()
                }
            }
        }

        /** Join live segments; no space before a segment that starts with punctuation. */
        private fun joined(extra: String = ""): String =
            (segments + listOf(extra)).filter { it.isNotBlank() }.fold("") { acc, t ->
                if (acc.isEmpty()) t else if (t[0] in ".,!?;:") acc + t else "$acc $t"
            }

        /** Realtime socket died or went quiet: finish via the saved file (now, or when you stop talking). */
        private fun liveFailed() {
            liveDead = true
            scribe.close()
            if (state == State.WAITING) viaFile()
        }

        private fun viaFile() {
            val kt = keyterms(); val auto = Settings.autoLanguage(this@FlowService)
            thread(name = "batch") {
                wavReady.await()
                try {
                    val text = Batch.transcribe(Settings.apiKey(this@FlowService), wav, kt, auto)
                    main.post { if (text.isBlank()) finishSilently() else deliver(text, retried = true) }
                } catch (e: Exception) {
                    main.post { failedAll(e.message ?: "network error") }
                }
            }
        }

        private fun failedAll(msg: String) {
            if (done) return
            done = true
            val partial = joined(lastPartial)
            History.add(this@FlowService, History.Entry(id, partial, wav.absolutePath, ok = false))
            if (partial.isNotBlank()) clip(partial)
            Nudge.failed(this@FlowService, id, partial)
            end("Last: failed — recording saved")
        }

        private fun deliver(raw: String, retried: Boolean = false) {
            if (done) return
            if (raw.isBlank()) { finishSilently(); return }
            done = true
            scribe.close()
            val ms = SystemClock.uptimeMillis() - releasedAt
            val text = raw.trim()
            if (text.isNotEmpty()) {
                // wav may still be flushing for a moment; the path is fixed so the entry is valid either way
                History.add(this@FlowService, History.Entry(id, text, wav.absolutePath, ok = true))
                clip(text)   // always in the clipboard, whatever happens next
                // Sometimes the box isn't focused yet the instant the text arrives (keyboard still coming
                // back after the bubble tap), so try again for ~2 s before falling back to the nudge
                // ("Dictation ready").
                val entryId = id
                thread(name = "insert") {
                    var result = InsertService.instance?.insert(text)
                    var tries = 0
                    while (result != InsertService.Result.OK && tries < 8) {
                        Thread.sleep(250); tries++
                        result = InsertService.instance?.insert(text)
                    }
                    if (result != InsertService.Result.OK) Nudge.ready(this@FlowService, entryId, text)
                    else {
                        Nudge.cancelResult(this@FlowService)
                        if (tries > 0) android.util.Log.i("ElevenFlow", "pasted on retry $tries")
                    }
                }
            }
            end(if (retried) "Last: $ms ms (via saved recording)" else "Last: $ms ms after stop")
        }

        private fun finishSilently() {
            done = true; scribe.close()
            thread { wavReady.await(); wav.delete() }
            end("Last: heard nothing")
        }

        private fun end(note: String) {
            getSystemService(NotificationManager::class.java).notify(1, notification(note))
            if (session === this) session = null
            render()
        }
    }

    /** 0..1 loudness of a PCM16 chunk: RMS in dB, -50 dB (quiet room) → 0, -12 dB (loud speech) → 1. */
    private fun level(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        for (i in 0 until pcm.size / 2) {
            val v = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort() / 32768.0
            sum += v * v
        }
        val db = 10 * Math.log10(sum / (pcm.size / 2) + 1e-10)
        return ((db + 50) / 38).toFloat().coerceIn(0f, 1f)
    }

    private fun keyterms() = Settings.keyterms(this)

    private fun clip(text: String) =
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dictation", text))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
