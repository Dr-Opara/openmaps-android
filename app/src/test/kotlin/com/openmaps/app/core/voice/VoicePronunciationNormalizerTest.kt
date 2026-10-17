package com.openmaps.app.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePronunciationNormalizerTest {

    @Test
    fun englishRoadAbbreviations_areExpandedForSpeech() {
        assertEquals(
            "Turn onto Westheimer Road and Memorial Drive.",
            VoicePronunciationNormalizer.normalize(
                "Turn onto Westheimer Rd and Memorial Dr.",
                VoiceLanguage.EnglishUs,
            ),
        )
    }

    @Test
    fun interstateAndUsHighway_areExpandedForSpeech() {
        assertEquals(
            "Take Interstate 10 toward U.S. Highway 59.",
            VoicePronunciationNormalizer.normalize(
                "Take I-10 toward US-59.",
                VoiceLanguage.EnglishUs,
            ),
        )
    }

    @Test
    fun doctorTitle_isNotExpandedAsDrive() {
        assertEquals(
            "Turn onto Dr. Martin Luther King Boulevard.",
            VoicePronunciationNormalizer.normalize(
                "Turn onto Dr. Martin Luther King Blvd.",
                VoiceLanguage.EnglishUs,
            ),
        )
    }

    @Test
    fun saintPrefix_isNotExpandedAsStreet() {
        assertEquals(
            "Turn onto St. James Place.",
            VoicePronunciationNormalizer.normalize(
                "Turn onto St. James Place.",
                VoiceLanguage.EnglishUk,
            ),
        )
    }

    @Test
    fun streetSuffix_isExpandedForSpeech() {
        assertEquals(
            "Turn onto Main Street.",
            VoicePronunciationNormalizer.normalize(
                "Turn onto Main St.",
                VoiceLanguage.EnglishUs,
            ),
        )
    }

    @Test
    fun frenchText_isNotAnglicized() {
        assertEquals(
            "Tournez sur Rue St. Denis.",
            VoicePronunciationNormalizer.normalize(
                "Tournez sur Rue St. Denis.",
                VoiceLanguage.French,
            ),
        )
    }
}
