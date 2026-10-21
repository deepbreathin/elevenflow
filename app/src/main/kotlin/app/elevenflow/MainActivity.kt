package app.elevenflow

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.provider.Settings as SysSettings

/** Setup button that walks through whatever is missing, then settings (all live), custom words, recent dictations. */
class MainActivity : Activity() {

    companion object { private const val PICK_IMAGE = 7 }

    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var history: LinearLayout
    private lateinit var keyField: EditText
    private lateinit var col: LinearLayout
    private val px get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * px).toInt()
        status = TextView(this).apply { textSize = 18f; setLineSpacing(0f, 1.4f) }
        button = Button(this).apply { textSize = 18f; setOnClickListener { next() } }
        history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        setContentView(ScrollView(this).apply { addView(col) })

        // Your own ElevenLabs key: stored only on this phone (app-private storage), sent only to api.elevenlabs.io.
        keyField = EditText(this).apply {
            hint = "ElevenLabs API key (sk_…)"
            isSingleLine = true   // must come before the masking: setSingleLine() resets it
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            typeface = android.graphics.Typeface.DEFAULT
            setText(getSharedPreferences("flow", MODE_PRIVATE).getString("apiKey", ""))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { Settings.put(this@MainActivity, "apiKey", e.toString().trim()); render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        col.addView(status)
        col.addView(keyField)
        col.addView(TextView(this).apply {
            text = "Get a key: elevenlabs.io → Developers → API keys (needs Speech to Text access)"
            textSize = 13f; alpha = 0.6f; setPadding(0, 0, 0, (12 * px).toInt())
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://elevenlabs.io/app/developers/api-keys"))) }
        })
        col.addView(button)

        heading("Bubble")
        slider("Size", 36, 240, Settings.sizeDp(this), "sizeDp") { "$it dp" }
        artRow()
        slider("See-through when idle", 10, 100, Settings.idleOpacity(this), "idleOpacity") { "$it% visible" }
        slider("See-through when recording", 10, 100, Settings.recOpacity(this), "recOpacity") { "$it% visible" }
        toggle("Only show when the keyboard is up", Settings.keyboardOnly(this), "kbonly")
        toggle("Sound meter while recording", Settings.meter(this), "meter")
        toggle("Vibrate on start / stop", Settings.vibrate(this), "vibrate")

        heading("Dictation")
        slider("Tap vs hold", 150, 800, Settings.holdMs(this), "holdMs") { "hold longer than $it ms = push-to-talk" }
        toggle("Remove filler words (um, uh) and stutters", Settings.cleanSpeech(this), "cleanSpeech")
        toggle("Detect language (off = English only, a bit more accurate)", Settings.autoLanguage(this), "autoLang")
        col.addView(EditText(this).apply {
            hint = "Custom words, comma separated (names, jargon)"
            setText(getSharedPreferences("flow", MODE_PRIVATE).getString("keyterms", ""))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { Settings.put(this@MainActivity, "keyterms", e.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        heading("Recent — tap to copy")
        col.addView(history)
    }

    private lateinit var artPreview: LinearLayout
    private lateinit var artReset: Button

    /** Bubble image: preview of idle + recording looks, choose your own, or go back to the robot. */
    private fun artRow() {
        val s = (56 * px).toInt()
        artPreview = LinearLayout(this).apply { setPadding(0, (8 * px).toInt(), 0, (4 * px).toInt()) }
        col.addView(TextView(this).apply { text = "Bubble image (idle · recording)"; textSize = 16f })
        col.addView(artPreview)
        col.addView(Button(this).apply {
            text = "Choose your own image…"; textSize = 16f
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), PICK_IMAGE)
            }
        })
        artReset = Button(this).apply {
            text = "Back to the robot"; textSize = 16f
            setOnClickListener { BubbleArt.reset(this@MainActivity); renderArt() }
        }
        col.addView(artReset)
        renderArt()
    }

    private fun renderArt() {
        val s = (56 * px).toInt()
        artPreview.removeAllViews()
        val (off, on) = BubbleArt.load(this)
        for (b in listOf(off, on)) artPreview.addView(android.widget.ImageView(this).apply {
            setImageBitmap(b); layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (12 * px).toInt() }
        })
        artReset.visibility = if (BubbleArt.hasCustom(this)) android.view.View.VISIBLE else android.view.View.GONE
    }

    @Deprecated("platform API; fine for a one-screen app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || uri == null) return
        Thread {
            val ok = runCatching { BubbleArt.setFrom(this, uri) }.getOrDefault(false)
            runOnUiThread { renderArt(); toast(if (ok) "Bubble image updated" else "Couldn't read that image") }
        }.start()
    }

    private fun heading(t: String) = col.addView(TextView(this).apply {
        text = t; textSize = 14f; alpha = 0.6f; setPadding(0, (28 * px).toInt(), 0, (6 * px).toInt())
    })

    private fun slider(title: String, min: Int, max: Int, value: Int, key: String, fmt: (Int) -> String) {
        val label = TextView(this).apply { textSize = 16f; text = "$title — ${fmt(value)}" }
        col.addView(label)
        col.addView(SeekBar(this).apply {
            this.max = max - min; progress = value - min
            setPadding((8 * px).toInt(), (10 * px).toInt(), (8 * px).toInt(), (14 * px).toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    label.text = "$title — ${fmt(p + min)}"
                    Settings.put(this@MainActivity, key, p + min)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
    }

    private fun toggle(title: String, value: Boolean, key: String) = col.addView(Switch(this).apply {
        text = title; textSize = 16f; isChecked = value
        setPadding(0, (8 * px).toInt(), 0, (8 * px).toInt())
        setOnCheckedChangeListener { _, on -> Settings.put(this@MainActivity, key, on) }
    })

    override fun onResume() {
        super.onResume()
        // opening the app always brings the bubble back if it should be on (e.g. after Android killed it)
        if (Settings.wantOn(this) && !FlowService.running && hasKey() && hasMic() && hasOverlay())
            startForegroundService(Intent(this, FlowService::class.java))
        render()
        window.decorView.postDelayed({ render() }, 300)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasOverlay() = SysSettings.canDrawOverlays(this)
    private fun hasA11y() = InsertService.instance != null
    private fun hasKey() = Settings.apiKey(this).isNotBlank()

    private fun render() {
        fun mark(ok: Boolean) = if (ok) "✓" else "·"
        status.text = "${mark(hasKey())}  ElevenLabs API key\n" +
            "${mark(hasMic())}  Microphone\n" +
            "${mark(hasOverlay())}  Draw over apps\n" +
            "${mark(hasA11y())}  Auto-paste (Accessibility → ElevenFlow typing)\n" +
            (if (!hasA11y()) "   greyed out? App info → ⋮ → Allow restricted settings\n" else "") + "\n"
        button.text = when {
            !hasKey() -> "Paste your ElevenLabs key above"
            !hasMic() -> "Allow microphone"
            !hasOverlay() -> "Allow draw over apps"
            !hasA11y() -> "Turn on auto-paste"
            FlowService.running -> "Stop bubble"
            else -> "Start bubble"
        }

        renderHistory()
    }

    private fun copy(t: String) =
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dictation", t))

    private fun renderHistory() {
        history.removeAllViews()
        val pad = (10 * px).toInt()
        History.all(this).forEach { e ->
            val hasAudio = e.audio != null && java.io.File(e.audio).exists()
            history.addView(TextView(this).apply {
                text = when {
                    e.ok -> e.text
                    e.text.isNotBlank() -> "⚠ Only partly transcribed: ${e.text}"
                    else -> "⚠ Not transcribed yet"
                }
                textSize = 15f; setPadding(0, pad, 0, if (hasAudio) 0 else pad)
                if (e.text.isNotBlank()) setOnClickListener { copy(e.text); toast("Copied") }
            })
            if (hasAudio) history.addView(TextView(this).apply {
                text = if (e.ok) "↻ transcribe again" else "↻ transcribe now"
                textSize = 13f; alpha = if (e.ok) 0.45f else 0.9f
                setPadding(0, (4 * px).toInt(), 0, pad)
                setOnClickListener { retranscribe(e, this) }
            })
        }
    }

    /** Send a saved recording through the whole-file endpoint; result replaces the entry and goes to the clipboard. */
    private fun retranscribe(e: History.Entry, label: TextView) {
        label.text = "… transcribing"
        val kt = Settings.keyterms(this); val auto = Settings.autoLanguage(this)
        Thread {
            val result = runCatching { Batch.transcribe(Settings.apiKey(this), java.io.File(e.audio!!), kt, auto, Settings.cleanSpeech(this))
                .let { Settings.tidy(this, it) } }
            runOnUiThread {
                result.onSuccess { t ->
                    History.update(this, e.id, t, ok = true)
                    if (t.isNotBlank()) { copy(t); toast("Transcribed — copied to clipboard") } else toast("Heard nothing in that recording")
                }.onFailure { toast("Still failing: ${it.message}") }
                renderHistory()
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun next() {
        when {
            !hasKey() -> { keyField.requestFocus(); getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(keyField, 0) }
            !hasMic() -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
            !hasOverlay() -> startActivity(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            !hasA11y() -> startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
            FlowService.running -> {
                Settings.put(this, "wantOn", false)
                stopService(Intent(this, FlowService::class.java)); window.decorView.postDelayed({ render() }, 200)
            }
            else -> {
                Settings.put(this, "wantOn", true)
                startForegroundService(Intent(this, FlowService::class.java)); moveTaskToBack(true)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = render()
}
