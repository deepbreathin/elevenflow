package app.elevenflow

import android.content.Context
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional webhook: POSTs {"event":"start"} when dictation starts and {"event":"stop"} when it ends,
 * so another device can react (pause a computer's music, dim lights, …). Off when the URL is empty.
 * Fire-and-forget: a slow or unreachable server never delays dictation.
 */
object Hook {
    private val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()
    @Volatile private var started = false

    fun start(ctx: Context) { if (!started) { started = true; post(ctx, "start") } }
    fun stop(ctx: Context) { if (started) { started = false; post(ctx, "stop") } }

    private fun post(ctx: Context, event: String) {
        val url = Settings.hookUrl(ctx).takeIf { it.startsWith("http") } ?: return
        val req = runCatching { Request.Builder().url(url).post("""{"event":"$event"}""".toRequestBody(json)).build() }
            .getOrNull() ?: return
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }
}
