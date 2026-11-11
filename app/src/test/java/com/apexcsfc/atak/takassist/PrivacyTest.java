package com.apexcsfc.atak.takassist;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PrivacyTest {
    @Test
    public void loopbackHostsAreOnThePhone() {
        assertTrue(Privacy.isLoopback("127.0.0.1"));
        assertTrue(Privacy.isLoopback("127.1.2.3"));
        assertTrue(Privacy.isLoopback("localhost"));
        assertTrue(Privacy.isLoopback("[::1]"));
    }

    @Test
    public void otherHostsAreNot() {
        assertFalse(Privacy.isLoopback("192.168.1.20"));
        assertFalse(Privacy.isLoopback("10.0.0.5"));
        assertFalse(Privacy.isLoopback("127.0.0.1.evil.com"));
        assertFalse(Privacy.isLoopback("localhost.evil.com"));
        assertFalse(Privacy.isLoopback("api.example.com"));
        assertFalse(Privacy.isLoopback(""));
        assertFalse(Privacy.isLoopback(null));
    }
}
