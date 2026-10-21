package app.elevenflow

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Whole-file transcription (POST /v1/speech-to-text). The retry path: a 75 s recording comes back
 * complete in ~3 s, whereas replaying over the realtime socket is capped near 2× speed (faster
 * gives queue_overflow). Blocking — call off the main thread.
 */
object Batch {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun transcribe(apiKey: String, wav: File, keyterms: List<String>, autoLanguage: Boolean, noVerbatim: Boolean = false): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model_id", "scribe_v2")
            .addFormDataPart("tag_audio_events", "false")
            .apply { if (!autoLanguage) addFormDataPart("language_code", "en") }
            .apply { if (noVerbatim) addFormDataPart("no_verbatim", "true") }
            .apply { keyterms.forEach { addFormDataPart("keyterms", it) } }
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url("https://api.elevenlabs.io/v1/speech-to-text")
            .header("xi-api-key", apiKey).post(body).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}: ${text.take(160)}")
            return JSONObject(text).optString("text").trim()
        }
    }
}
