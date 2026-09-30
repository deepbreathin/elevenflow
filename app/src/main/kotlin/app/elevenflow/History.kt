package app.elevenflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Last 20 dictations, newest first. Each keeps its recording, so it can be transcribed again;
 * ok=false means it has not been transcribed successfully yet (text may be a partial or empty).
 */
object History {
    private const val MAX = 20

    data class Entry(val id: Long, val text: String, val audio: String?, val ok: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("flow", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Entry> {
        val a = JSONArray(prefs(ctx).getString("history", "[]"))
        return (0 until a.length()).map { i ->
            when (val v = a.get(i)) {
                is JSONObject -> Entry(v.optLong("id"), v.optString("text"),
                    v.optString("audio").ifEmpty { null }, v.optBoolean("ok", true))
                else -> Entry(0, v.toString(), null, true)   // entries from before recordings were kept
            }
        }
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        list.take(MAX).forEach {
            a.put(JSONObject().put("id", it.id).put("text", it.text).put("audio", it.audio ?: "").put("ok", it.ok))
        }
        prefs(ctx).edit().putString("history", a.toString()).apply()
        Recordings.prune(ctx, list.take(MAX).mapNotNull { it.audio }.toSet())
    }

    @Synchronized
    fun add(ctx: Context, e: Entry) = save(ctx, listOf(e) + all(ctx).filter { it.id != e.id || e.id == 0L })

    @Synchronized
    fun update(ctx: Context, id: Long, text: String, ok: Boolean) =
        save(ctx, all(ctx).map { if (it.id == id) it.copy(text = text, ok = ok) else it })
}
