package net.bitsar.sonify.core.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClassNameShortenerTest {

    @Test
    @DisplayName("one package segment is kept for context")
    void keepsOneSegmentOfContext() {
        assertEquals("example.Cache", ClassNameShortener.shorten("com.example.Cache"));
        assertEquals("lang.Object", ClassNameShortener.shorten("java.lang.Object"));
        assertEquals("cache.UserCache", ClassNameShortener.shorten("com.example.cache.UserCache"));
    }

    @Test
    @DisplayName("names without a package are left alone")
    void unpackagedNames() {
        assertEquals("Cache", ClassNameShortener.shorten("Cache"));
        assertEquals("int[]", ClassNameShortener.shorten("int[]"));
    }

    @Test
    @DisplayName("a missing monitor class reads as unknown")
    void missingNames() {
        assertEquals("unknown", ClassNameShortener.shorten(null));
        assertEquals("unknown", ClassNameShortener.shorten(""));
    }
}
