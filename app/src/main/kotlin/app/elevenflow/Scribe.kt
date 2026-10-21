package app.elevenflow

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One ElevenLabs Scribe v2 Realtime session = one utterance. Audio sent before the socket opens is queued. */
class Scribe(private val listener: Listener, apiKey: String, keyterms: List<String> = emptyList(), autoLanguage: Boolean = false,
             noVerbatim: Boolean = false) {

    interface Listener {
        fun onPartial(text: String)
        fun onCommitted(text: String)
        fun onError(msg: String)
    }

    companion object {
        private const val URL = "wss://api.elevenlabs.io/v1/speech-to-text/realtime" +
            "?model_id=scribe_v2_realtime&audio_format=pcm_16000&commit_strategy=manual"
        private val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    private val lock = Object()
    private var ready = false
    private val queue = ArrayList<String>()
    var closed = false; private set

    private val ws: WebSocket = http.newWebSocket(
        Request.Builder().url(URL + (if (autoLanguage) "" else "&language_code=en") + (if (noVerbatim) "&no_verbatim=true" else "") + keyterms.joinToString("") { "&keyterms=" + java.net.URLEncoder.encode(it, "UTF-8") }).header("xi-api-key", apiKey).build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    queue.forEach { webSocket.send(it) }
                    queue.clear()
                    ready = true
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = JSONObject(text)
                when (val type = m.optString("message_type")) {
                    "session_started" -> {}
                    "partial_transcript" -> listener.onPartial(m.optString("text"))
                    "committed_transcript" -> listener.onCommitted(m.optString("text"))
                    else -> if (m.has("error")) listener.onError("$type: ${m.optString("error")}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) listener.onError(response?.let { "HTTP ${it.code} ${it.message}" } ?: (t.message ?: "socket failed"))
            }
        })

    fun send(pcm: ByteArray, commit: Boolean) {
        val msg = JSONObject()
            .put("message_type", "input_audio_chunk")
            .put("audio_base_64", Base64.encodeToString(pcm, Base64.NO_WRAP))
            .put("commit", commit)
            .put("sample_rate", 16000)
            .toString()
        synchronized(lock) { if (ready) ws.send(msg) else queue.add(msg) }
    }

    fun close() {
        closed = true
        ws.close(1000, null)
    }
}
