package com.openmaps.app.core.voice

/**
 * Conservative pronunciation cleanup applied only to navigation phrases sent to neural TTS.
 *
 * It expands common road abbreviations and numbered-highway forms instead of asking the model to
 * guess how terse map labels should sound. The visible street name is never modified.
 */
object VoicePronunciationNormalizer {

    fun normalize(text: String, language: VoiceLanguage): String {
        if (
            language != VoiceLanguage.EnglishUs &&
            language != VoiceLanguage.EnglishUk &&
            language != VoiceLanguage.NigerianPidgin
        ) return text

        var out = text
        substitutions.forEach { (pattern, replacement) ->
            out = out.replace(pattern, replacement)
        }
        out = out.replace(Regex("""\bI[- ](\d{1,3})\b""", RegexOption.IGNORE_CASE), "Interstate \$1")
        out = out.replace(Regex("""\bUS[- ](\d{1,3})\b""", RegexOption.IGNORE_CASE), "U.S. Highway \$1")
        return out
    }

    private val substitutions = listOf(
        Regex("""\bBlvd(?=\.?(?:\s|,|$))""", RegexOption.IGNORE_CASE) to "Boulevard",
        Regex("""\bPkwy(?=\.?(?:\s|,|$))""", RegexOption.IGNORE_CASE) to "Parkway",
        Regex("""\bHwy(?=\.?(?:\s|,|$))""", RegexOption.IGNORE_CASE) to "Highway",
        Regex("""\bAve(?=\.?(?:\s|,|$))""", RegexOption.IGNORE_CASE) to "Avenue",
        Regex("""\bRd(?=\.?(?:\s|,|$))""", RegexOption.IGNORE_CASE) to "Road",
        Regex("""\bLn(?=\.?(?:\s|,|$))""", RegexOption.IGNORE_CASE) to "Lane",
        Regex("""\bCt(?=\.?(?:\s|,|$))""", RegexOption.IGNORE_CASE) to "Court",
        Regex("""\bCir(?=\.?(?:\s|,|$))""", RegexOption.IGNORE_CASE) to "Circle",
        Regex("""\bDr(?=\.?(?:\s+(?:N|S|E|W|NE|NW|SE|SW)\b|\s+(?:and|toward|onto|then)\b|[,;]|$))""", RegexOption.IGNORE_CASE) to "Drive",
        Regex("""\bSt(?=\.?(?:\s+(?:N|S|E|W|NE|NW|SE|SW)\b|\s+(?:and|toward|onto|then)\b|[,;]|$))""", RegexOption.IGNORE_CASE) to "Street",
    )
}
