package net.bitsar.sonify.core.jfr;

/**
 * Shortens a binary class name for display in the event log, keeping the enclosing package's
 * last segment for context: {@code com.example.cache.UserCache} becomes {@code cache.UserCache}.
 */
public final class ClassNameShortener {

    private ClassNameShortener() {
    }

    public static String shorten(String className) {
        if (className == null || className.isEmpty()) {
            return "unknown";
        }
        char[] characters = className.toCharArray();
        int lastDot = -1;
        int previousDot = -1;
        for (int index = 0; index < characters.length; index++) {
            if (characters[index] == '.') {
                previousDot = lastDot;
                lastDot = index;
            }
        }
        if (lastDot < 0) {
            return className;
        }
        return className.substring(previousDot + 1);
    }
}
