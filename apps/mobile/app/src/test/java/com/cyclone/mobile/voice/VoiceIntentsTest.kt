package com.cyclone.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Instant commands: understood on the phone, no model, and never a guess. */
class VoiceIntentsTest {
    private fun goal(text: String) = VoiceIntents.parse(text)?.goal

    @Test fun `timers in the ways people say them`() {
        assertEquals("Set a timer for 10 minutes.", goal("Set a timer for 10 minutes."))
        assertEquals("Set a timer for 10 minutes.", goal("ten minute timer"))
        assertEquals("Set a timer for 25 minutes.", goal("set a timer for twenty five minutes"))
        assertEquals("Set a timer for 1 hour and 30 minutes.", goal("timer 1 hour and 30 minutes"))
        assertEquals("Set a timer for 30 minutes.", goal("set a timer for half an hour"))
        assertEquals("Set a timer for 1 minute.", goal("timer for a minute"))
        assertEquals("Set a timer for 45 seconds.", goal("Cyclone, start a timer for 45 seconds please"))
        assertEquals("Set a timer for 5 minutes.", goal("zet een timer van 5 minuten"))
        assertEquals("Set a timer for 30 minutes.", goal("zet een timer van een half uur"))
        assertEquals("Setting a 10 minute timer.", VoiceIntents.parse("ten minute timer")?.ack)
    }

    @Test fun `alarms`() {
        assertEquals("Set an alarm for 7:30.", goal("set an alarm for 7:30"))
        assertEquals("Set an alarm for 6:00 AM.", goal("wake me up at 6 am"))
        assertEquals("Set an alarm for 7:30.", goal("wake me at seven thirty"))
        assertEquals("Set an alarm for 9:15 PM.", goal("alarm at 9:15 pm"))
        assertEquals("Set an alarm for 7:00.", goal("zet een wekker om 7 uur"))
    }

    @Test fun `anything more goes to the model`() {
        for (text in listOf("set a timer for 10 minutes for the pasta", "remind me in 10 minutes to call mom",
                "set an alarm tomorrow at 7", "what's my next timer", "timer", "alarm at 25:00", "set a timer for 30 hours",
                "wake me when we're there", "reply to Louella")) {
            assertNull(text, VoiceIntents.parse(text))
        }
    }
}

class VoiceModelOrderTest {
    private val live = listOf(VoiceModel("openai/whisper-large-v3-turbo", "Whisper"), VoiceModel("microsoft/mai-transcribe-2", "MAI"),
        VoiceModel("meta/muse-voice-transcribe-1.0", "Muse"), VoiceModel("google/gemini-3.8-flash-lite-tts", "Lite TTS"),
        VoiceModel("google/gemini-3.8-flash-tts", "Flash TTS"))

    @Test fun `the newest speech models come first when listed`() {
        assertEquals("meta/muse-voice-transcribe-1.0", VoiceModels.pick(live, VoiceModels.PREFERRED_STT))
        assertEquals("microsoft/mai-transcribe-2", VoiceModels.pick(live.filterNot { it.id.startsWith("meta/") }, VoiceModels.PREFERRED_STT))
    }

    @Test fun `best voice picks the most natural listed voice, fast the quickest`() {
        assertEquals("google/gemini-3.8-flash-lite-tts", VoiceModels.choose(emptyList(), live, emptyList(), DriverSettings()).tts)
        assertEquals("google/gemini-3.8-flash-tts", VoiceModels.choose(emptyList(), live, emptyList(), DriverSettings(bestVoice = true)).tts)
    }
}
