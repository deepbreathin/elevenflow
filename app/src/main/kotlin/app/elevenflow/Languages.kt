package app.elevenflow

import android.content.Context

/**
 * The languages you can dictate in. You tick the ones you speak; the bubble's small language chip (and the
 * notification) switches between them. "auto" means let ElevenLabs guess — it can mix up languages that
 * sound alike (e.g. Armenian heard as Russian), so picking the language is more reliable.
 *
 * Codes are what ElevenLabs Scribe takes as `language_code` (ISO 639-1, or ISO 639-3 where there's no
 * two-letter code).
 */
object Languages {
    const val AUTO = "auto"

    class Lang(val code: String, val name: String) {
        /** Short label for the bubble chip, e.g. "EN", "HY", "AUTO". */
        val badge get() = if (code == AUTO) "AUTO" else code.uppercase()
    }

    val all: List<Lang> = listOf(
        Lang(AUTO, "Detect automatically"),
        Lang("en", "English"),
        Lang("af", "Afrikaans"), Lang("am", "Amharic"), Lang("ar", "Arabic"), Lang("hy", "Armenian"),
        Lang("as", "Assamese"), Lang("ast", "Asturian"), Lang("az", "Azerbaijani"), Lang("be", "Belarusian"),
        Lang("bn", "Bengali"), Lang("bs", "Bosnian"), Lang("bg", "Bulgarian"), Lang("my", "Burmese"),
        Lang("yue", "Cantonese"), Lang("ca", "Catalan"), Lang("ceb", "Cebuano"), Lang("ny", "Chichewa"),
        Lang("zh", "Chinese (Mandarin)"), Lang("hr", "Croatian"), Lang("cs", "Czech"), Lang("da", "Danish"),
        Lang("nl", "Dutch"), Lang("et", "Estonian"), Lang("fil", "Filipino"), Lang("fi", "Finnish"),
        Lang("fr", "French"), Lang("ff", "Fula"), Lang("gl", "Galician"), Lang("lg", "Ganda"),
        Lang("ka", "Georgian"), Lang("de", "German"), Lang("el", "Greek"), Lang("gu", "Gujarati"),
        Lang("ha", "Hausa"), Lang("he", "Hebrew"), Lang("hi", "Hindi"), Lang("hu", "Hungarian"),
        Lang("is", "Icelandic"), Lang("ig", "Igbo"), Lang("id", "Indonesian"), Lang("ga", "Irish"),
        Lang("it", "Italian"), Lang("ja", "Japanese"), Lang("jv", "Javanese"), Lang("kea", "Kabuverdianu"),
        Lang("kn", "Kannada"), Lang("kk", "Kazakh"), Lang("km", "Khmer"), Lang("ko", "Korean"),
        Lang("ku", "Kurdish"), Lang("ky", "Kyrgyz"), Lang("lo", "Lao"), Lang("lv", "Latvian"),
        Lang("ln", "Lingala"), Lang("lt", "Lithuanian"), Lang("luo", "Luo"), Lang("lb", "Luxembourgish"),
        Lang("mk", "Macedonian"), Lang("ms", "Malay"), Lang("ml", "Malayalam"), Lang("mt", "Maltese"),
        Lang("mi", "Māori"), Lang("mr", "Marathi"), Lang("mn", "Mongolian"), Lang("ne", "Nepali"),
        Lang("nso", "Northern Sotho"), Lang("no", "Norwegian"), Lang("oc", "Occitan"), Lang("or", "Odia"),
        Lang("ps", "Pashto"), Lang("fa", "Persian"), Lang("pl", "Polish"), Lang("pt", "Portuguese"),
        Lang("pa", "Punjabi"), Lang("ro", "Romanian"), Lang("ru", "Russian"), Lang("sr", "Serbian"),
        Lang("sn", "Shona"), Lang("sd", "Sindhi"), Lang("sk", "Slovak"), Lang("sl", "Slovenian"),
        Lang("so", "Somali"), Lang("es", "Spanish"), Lang("sw", "Swahili"), Lang("sv", "Swedish"),
        Lang("ta", "Tamil"), Lang("tg", "Tajik"), Lang("te", "Telugu"), Lang("th", "Thai"),
        Lang("tr", "Turkish"), Lang("uk", "Ukrainian"), Lang("umb", "Umbundu"), Lang("ur", "Urdu"),
        Lang("uz", "Uzbek"), Lang("vi", "Vietnamese"), Lang("cy", "Welsh"), Lang("wo", "Wolof"),
        Lang("xh", "Xhosa"), Lang("zu", "Zulu"),
    )

    private val byCode = all.associateBy { it.code }
    fun of(code: String): Lang = byCode[code] ?: Lang(code, code)

    private fun p(ctx: Context) = ctx.getSharedPreferences("flow", Context.MODE_PRIVATE)

    /** The languages you ticked, in list order. Never empty. Older installs carry over their "Detect language" switch. */
    fun enabled(ctx: Context): List<String> {
        val raw = p(ctx).getString("langs", null)
            ?: return listOf(if (p(ctx).getBoolean("autoLang", false)) AUTO else "en")
        val set = raw.split(",").map { it.trim() }.filter { it in byCode }.toSet()
        return all.map { it.code }.filter { it in set }.ifEmpty { listOf("en") }
    }

    fun setEnabled(ctx: Context, codes: Collection<String>) {
        val list = all.map { it.code }.filter { it in codes }.ifEmpty { listOf("en") }
        val e = p(ctx).edit().putString("langs", list.joinToString(","))
        if (current(ctx) !in list) e.putString("lang", list.first())
        e.apply()
    }

    /** The language the next dictation uses: one of [enabled]. */
    fun current(ctx: Context): String {
        val on = enabled(ctx)
        return p(ctx).getString("lang", null)?.takeIf { it in on } ?: on.first()
    }

    fun setCurrent(ctx: Context, code: String) = p(ctx).edit().putString("lang", code).apply()

    /** Step to the next ticked language (wrapping round); returns it. */
    fun cycle(ctx: Context): Lang {
        val on = enabled(ctx)
        val next = on[(on.indexOf(current(ctx)) + 1) % on.size]
        setCurrent(ctx, next)
        return of(next)
    }

    /** What to send as `language_code`; null = let ElevenLabs detect it. */
    fun code(ctx: Context): String? = current(ctx).takeIf { it != AUTO }
}
