package app.elevenflow

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupTest {
    private fun check(input: String, expected: String) = assertEquals(input, expected, Cleanup.clean(input))

    // real ElevenLabs output from stuttery test audio
    @Test fun realtimeVerbatim() = check(
        "So, um, I, I, I want to, uh, go to the LSH shop, um, tomorrow morning.",
        "So I want to go to the LSH shop tomorrow morning.")
    @Test fun batchVerbatim() = check(
        "So, um, I-I-I want to, uh, go to the SH shop, erm, tomorrow morning",
        "So I want to go to the shop tomorrow morning")
    @Test fun repeatedPhraseWithFiller() = check(
        "Can you, uh, can you maybe book the, um, the CC car for, for Friday?",
        "Can you maybe book the CC car for Friday?")

    // stutters
    @Test fun hyphenStutter() = check("I need to b-b-book the c-c-car.", "I need to book the car.")
    @Test fun fragmentBeforeWord() = check("Let's go to the sh shop now", "Let's go to the shop now")
    @Test fun repeatedFragment() = check("pick up the c c car", "pick up the car")
    @Test fun repeatedWordWithCommas() = check("I, I, I think so", "I think so")
    @Test fun repeatedRun() = check("we should we should leave", "we should leave")
    @Test fun fillerAtStart() = check("Um, so I was thinking", "So I was thinking")
    @Test fun fillerAtEnd() = check("See you tomorrow, um.", "See you tomorrow.")

    // must NOT change
    @Test fun keepsHyphenatedWords() = check("A well-known re-enter e-mail co-op", "A well-known re-enter e-mail co-op")
    @Test fun keepsThatThat() = check("I know that that is true.", "I know that that is true.")
    @Test fun keepsShortRealWords() = check("I am at a cafe in town", "I am at a cafe in town")
    @Test fun keepsLikeAndYouKnow() = check("It was like, you know, fine", "It was like, you know, fine")
    @Test fun keepsAcrossSentences() = check("Go. Go now.", "Go. Go now.")
    @Test fun keepsPrefixRealWord() = check("to today is fine", "to today is fine")
    @Test fun emptyStays() = check("", "")
}
