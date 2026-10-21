package app.elevenflow

/**
 * Removes filler sounds and stutters from a transcript, without an LLM (instant, offline, predictable).
 *
 * ElevenLabs' own `no_verbatim` option is also turned on, but in testing it was inconsistent
 * (kept "uh"/"um" and repeated phrases in some clips), so this pass runs on every result:
 *
 *   fillers        "want to, uh, go"            → "want to go"
 *   repeated words "I, I, I want" / "I-I-I"     → "I want"
 *   repeated runs  "can you, can you book"      → "can you book"
 *   fragments      "b-b-book", "sh shop", "c c car" → "book", "shop", "car"
 *
 * It deliberately leaves alone: hyphenated words whose parts aren't stutters ("well-known", "re-enter"),
 * real short words ("a", "I", "to"), and doubles that are normal English ("that that", "had had").
 * "like" / "you know" are not touched: too often they carry meaning.
 */
object Cleanup {

    private val FILLERS = setOf(
        "um", "umm", "ummm", "uh", "uhh", "uhm", "uhmm", "er", "erm", "err", "ah", "ahh",
        "hmm", "hm", "hmmm", "mm", "mmm", "eh",
    )

    /** Short real words that must never be mistaken for the start of a stuttered word. */
    private val REAL_SHORT = setOf(
        "a", "i", "an", "am", "as", "at", "be", "by", "do", "go", "he", "if", "in", "is", "it", "me",
        "my", "no", "of", "oh", "ok", "on", "or", "so", "to", "up", "us", "we", "ye", "yo",
        "the", "and", "are", "but", "can", "for", "get", "had", "has", "her", "him", "his", "how",
        "its", "let", "may", "not", "now", "off", "one", "our", "out", "own", "say", "see", "she",
        "too", "two", "use", "was", "way", "who", "why", "yes", "yet", "you", "all", "any", "car",
        "day", "new", "old", "put", "run", "set", "top", "try", "big", "bit", "cup", "eat", "far",
    )

    /** Doubled words that are normal English, kept when they appear exactly twice. */
    private val OK_TWICE = setOf("that", "had", "is", "very", "really", "so", "no", "yes", "bye", "ha", "now", "do")

    private data class Tok(var text: String) {
        val core get() = text.trim { !it.isLetterOrDigit() && it != '\'' }.lowercase()
        val trail get() = text.takeLastWhile { !it.isLetterOrDigit() && it != '\'' }
        fun withoutTrail() = text.dropLastWhile { !it.isLetterOrDigit() && it != '\'' }
    }

    fun clean(input: String): String {
        if (input.isBlank()) return input
        var toks = input.trim().split(Regex("\\s+")).map { Tok(collapseHyphenStutter(it)) }.toMutableList()

        // repeat until nothing changes: removing one thing can expose another ("the, um, the car")
        while (true) {
            val before = toks.joinToString(" ") { it.text }
            toks = dropFillers(toks)
            toks = dropFragments(toks)
            toks = dropRepeatedWords(toks)
            toks = dropRepeatedRuns(toks)
            if (toks.joinToString(" ") { it.text } == before) break
        }

        var out = toks.joinToString(" ") { it.text }
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex(",{2,}"), ",")
            .replace(Regex(",([.!?])"), "$1")
            .replace(Regex("^[,;:\\s]+"), "")
            .trim()
        if (out.isNotEmpty() && out[0].isLowerCase() && input.trim().firstOrNull()?.isUpperCase() == true)
            out = out[0].uppercaseChar() + out.substring(1)
        return out
    }

    /** "b-b-book" → "book", "I-I-I" → "I"; keeps "well-known", "re-enter", "e-mail". */
    private fun collapseHyphenStutter(word: String): String {
        val lead = word.takeWhile { !it.isLetterOrDigit() }
        if (lead.length == word.length) return word   // only punctuation ("...", "—"): nothing to collapse
        val trail = word.takeLastWhile { !it.isLetterOrDigit() }
        val body = word.substring(lead.length, word.length - trail.length)
        val parts = body.split('-')
        if (parts.size < 2 || parts.any { it.isEmpty() }) return word
        val last = parts.last()
        val stutter = parts.dropLast(1).all { p -> p.length <= 3 && last.lowercase().startsWith(p.lowercase()) }
        return if (stutter) lead + last + trail else word
    }

    private fun dropFillers(t: MutableList<Tok>): MutableList<Tok> {
        val out = mutableListOf<Tok>()
        for (tok in t) {
            if (tok.core !in FILLERS) { out += tok; continue }
            val prev = out.lastOrNull()
            when {
                // "to, uh, go": the commas only existed around the filler
                tok.trail.startsWith(",") && prev != null && prev.trail == "," -> prev.text = prev.withoutTrail()
                // "…tomorrow, um." / "…ten, uh?": keep the sentence ending
                tok.trail.any { it in ".!?" } && prev != null ->
                    prev.text = prev.withoutTrail() + tok.trail.filter { it in ".!?" }.take(1)
            }
        }
        return out
    }

    /** "sh shop" / "c car" → drop the fragment when it's a short non-word prefix of the next word. */
    private fun dropFragments(t: MutableList<Tok>): MutableList<Tok> {
        val out = mutableListOf<Tok>()
        for (i in t.indices) {
            val c = t[i].core
            val next = t.getOrNull(i + 1)?.core
            val fragment = next != null && c.length in 1..3 && c != next && c.all { it.isLetter() } &&
                c !in REAL_SHORT && next.startsWith(c) && t[i].trail.none { it in ".!?" }
            if (!fragment) out += t[i]
        }
        return out
    }

    /** "I, I, I want" → "I want"; "for for" → "for"; "that that" (exactly twice) is kept. */
    private fun dropRepeatedWords(t: MutableList<Tok>): MutableList<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < t.size) {
            var j = i
            while (j + 1 < t.size && t[j + 1].core == t[i].core && t[i].core.isNotEmpty() &&
                t[j].trail.none { it in ".!?" }) j++
            val count = j - i + 1
            if (count == 1 || (count == 2 && t[i].core in OK_TWICE)) for (k in i..j) out += t[k]
            else out += t[j]   // keep the last copy: it carries the right punctuation
            i = j + 1
        }
        return out
    }

    /** "can you, can you book" → "can you book" (runs of 2–4 words said twice in a row). */
    private fun dropRepeatedRuns(t: MutableList<Tok>): MutableList<Tok> {
        for (n in 4 downTo 2) {
            var i = 0
            while (i + 2 * n <= t.size) {
                val a = (i until i + n).map { t[it].core }
                val b = (i + n until i + 2 * n).map { t[it].core }
                val crossesSentence = (i until i + n).any { k -> t[k].trail.any { it in ".!?" } }
                if (a == b && a.all { it.isNotEmpty() } && !crossesSentence) {
                    repeat(n) { t.removeAt(i) }
                } else i++
            }
        }
        return t
    }
}
