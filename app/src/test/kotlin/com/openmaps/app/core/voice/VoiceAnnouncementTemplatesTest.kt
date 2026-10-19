package com.openmaps.app.core.voice

import com.openmaps.app.core.localization.MeasurementSystem
import com.openmaps.app.core.routing.ManeuverType
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic-template coverage (delta-spec sections 20-22): every [VoiceEvent] and every
 * [ManeuverType] must produce a real, non-blank phrase in every supported language — no language may
 * silently fall through to an empty string, which is the template-table equivalent of a crash.
 */
class VoiceAnnouncementTemplatesTest {

    @Test
    fun everyFixedEvent_hasANonBlankPhrase_inEveryLanguage() {
        val events = listOf(
            VoiceEvent.NavigationStarted,
            VoiceEvent.Rerouting,
            VoiceEvent.RouteUpdated,
            VoiceEvent.DestinationApproaching,
            VoiceEvent.Arrived,
        )
        for (language in VoiceLanguage.entries) {
            for (event in events) {
                val phrase = VoiceAnnouncementTemplates.phraseFor(event, language)
                assertTrue("$language/$event produced a blank phrase", phrase.isNotBlank())
            }
        }
    }

    @Test
    fun everyManeuverType_everyStage_hasANonBlankPhrase_inEveryLanguage() {
        for (language in VoiceLanguage.entries) {
            for (type in ManeuverType.entries) {
                for (stage in AnnouncementStage.entries) {
                    val phrase = VoiceAnnouncementTemplates.phraseFor(
                        VoiceEvent.Maneuver(type, streetName = null, stage = stage),
                        language,
                    )
                    assertTrue("$language/$type/$stage produced a blank phrase", phrase.isNotBlank())
                }
            }
        }
    }

    @Test
    fun streetNameIsIncludedWhenPresent() {
        val withStreet = VoiceAnnouncementTemplates.phraseFor(
            VoiceEvent.Maneuver(ManeuverType.TurnRight, "Main Street", AnnouncementStage.Immediate),
            VoiceLanguage.EnglishUs,
        )
        assertTrue(withStreet.contains("Main Street"))
    }

    @Test
    fun blankStreetName_isTreatedAsAbsent() {
        val phrase = VoiceAnnouncementTemplates.phraseFor(
            VoiceEvent.Maneuver(ManeuverType.TurnRight, "   ", AnnouncementStage.Immediate),
            VoiceLanguage.EnglishUs,
        )
        assertFalse(phrase.contains("onto"))
    }

    @Test
    fun immediateStage_hasNoDistancePrefix() {
        val phrase = VoiceAnnouncementTemplates.phraseFor(
            VoiceEvent.Maneuver(ManeuverType.TurnLeft, null, AnnouncementStage.Immediate),
            VoiceLanguage.EnglishUs,
        )
        assertEquals("Turn left.", phrase)
    }

    @Test
    fun farAndNearStages_useTheSpecsCanonicalDistancePhrasing() {
        val far = VoiceAnnouncementTemplates.phraseFor(
            VoiceEvent.Maneuver(ManeuverType.TurnRight, "Main Street", AnnouncementStage.Far),
            VoiceLanguage.EnglishUs,
        )
        assertEquals("In half a mile, turn right onto Main Street.", far)

        val near = VoiceAnnouncementTemplates.phraseFor(
            VoiceEvent.Maneuver(ManeuverType.TurnRight, "Main Street", AnnouncementStage.Near),
            VoiceLanguage.EnglishUs,
        )
        assertEquals("In 500 feet, turn right onto Main Street.", near)
    }

    @Test
    fun arrivedPhrase_matchesEachLanguagesRealWording() {
        assertEquals("You have arrived.", VoiceAnnouncementTemplates.phraseFor(VoiceEvent.Arrived, VoiceLanguage.EnglishUs))
        assertEquals(
            "You don reach your destination.",
            VoiceAnnouncementTemplates.phraseFor(VoiceEvent.Arrived, VoiceLanguage.NigerianPidgin),
        )
        assertEquals("Ha llegado a su destino.", VoiceAnnouncementTemplates.phraseFor(VoiceEvent.Arrived, VoiceLanguage.Spanish))
        assertEquals(
            "Vous êtes arrivé à destination.",
            VoiceAnnouncementTemplates.phraseFor(VoiceEvent.Arrived, VoiceLanguage.French),
        )
    }

    @Test
    fun englishUsAndEnglishUk_shareTheSameWording() {
        // Section 19: the two differ by TTS locale/voice, not by word choice.
        for (type in ManeuverType.entries) {
            for (stage in AnnouncementStage.entries) {
                val event = VoiceEvent.Maneuver(type, "Elm Street", stage)
                assertEquals(
                    VoiceAnnouncementTemplates.phraseFor(event, VoiceLanguage.EnglishUs),
                    VoiceAnnouncementTemplates.phraseFor(event, VoiceLanguage.EnglishUk),
                )
            }
        }
    }

    @Test
    fun metricNavigation_usesMetricDistancePhrasing() {
        val phrase = VoiceAnnouncementTemplates.phraseFor(
            VoiceEvent.Maneuver(ManeuverType.TurnRight, "Rue de Rivoli", AnnouncementStage.Near),
            VoiceLanguage.French,
            MeasurementSystem.Metric,
            Locale.FRANCE,
        )
        assertEquals("Dans 150 mètres, tournez à droite sur Rue de Rivoli.", phrase)
    }

    @Test
    fun ukAutomaticNavigation_usesYardsForNearPrompt() {
        val phrase = VoiceAnnouncementTemplates.phraseFor(
            VoiceEvent.Maneuver(ManeuverType.TurnLeft, "Oxford Street", AnnouncementStage.Near),
            VoiceLanguage.EnglishUk,
            MeasurementSystem.Auto,
            Locale.UK,
        )
        assertEquals("In 200 yards, turn left onto Oxford Street.", phrase)
    }

    @Test
    fun rtlLanguages_haveNativeNavigationPhrases() {
        assertEquals(
            "لقد وصلت إلى وجهتك.",
            VoiceAnnouncementTemplates.phraseFor(VoiceEvent.Arrived, VoiceLanguage.Arabic),
        )
        assertEquals(
            "הגעתם ליעד.",
            VoiceAnnouncementTemplates.phraseFor(VoiceEvent.Arrived, VoiceLanguage.Hebrew),
        )
    }

    @Test
    fun nigerianPidgin_isRespectfulAndUnderstandable_notExaggerated() {
        // Spot-check a couple of the spec's own example phrasings are present verbatim.
        val arrived = VoiceAnnouncementTemplates.phraseFor(VoiceEvent.Arrived, VoiceLanguage.NigerianPidgin)
        assertEquals("You don reach your destination.", arrived)
    }
}
