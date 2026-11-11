package com.apexcsfc.atak.takassist.talk.speech;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SpeechCleanTest {
    @Test
    public void dropsWhisperTags() {
        assertEquals("", SpeechInput.clean(" [BLANK_AUDIO] "));
        assertEquals("Enemy truck 200 meters north.", SpeechInput.clean("[MUSIC] Enemy truck 200 meters north. (wind)"));
        assertEquals("grid 18S UJ 2421 0958", SpeechInput.clean("  grid 18S UJ 2421   0958 "));
    }
}
