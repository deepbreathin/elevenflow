package app.elevenflow

import android.content.Context

/** Every user setting in one place, with its default. The bubble re-reads these live. */
object Settings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("flow", Context.MODE_PRIVATE)

    fun sizeDp(ctx: Context) = p(ctx).getInt("sizeDp", 60)                  // 36..110
    fun idleOpacity(ctx: Context) = p(ctx).getInt("idleOpacity", 37)        // percent
    fun recOpacity(ctx: Context) = p(ctx).getInt("recOpacity", 65)          // percent
    fun holdMs(ctx: Context) = p(ctx).getInt("holdMs", 300)                 // tap vs hold cut-off
    fun keyboardOnly(ctx: Context) = p(ctx).getBoolean("kbonly", true)
    fun meter(ctx: Context) = p(ctx).getBoolean("meter", true)
    fun vibrate(ctx: Context) = p(ctx).getBoolean("vibrate", true)
    fun autoLanguage(ctx: Context) = p(ctx).getBoolean("autoLang", false)   // false = English only (faster, fewer mis-hears)
    /** Your ElevenLabs key, entered in the app. Falls back to one baked in at build time (secrets.properties), if any. */
    fun apiKey(ctx: Context) = p(ctx).getString("apiKey", "")!!.trim().ifEmpty { BuildConfig.ELEVEN_KEY }
    fun wantOn(ctx: Context) = p(ctx).getBoolean("wantOn", true)          // bubble should be running
    fun keyterms(ctx: Context) = p(ctx).getString("keyterms", "")!!
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun put(ctx: Context, key: String, v: Int) = p(ctx).edit().putInt(key, v).apply()
    fun put(ctx: Context, key: String, v: Boolean) = p(ctx).edit().putBoolean(key, v).apply()
    fun put(ctx: Context, key: String, v: String) = p(ctx).edit().putString(key, v).apply()
}
