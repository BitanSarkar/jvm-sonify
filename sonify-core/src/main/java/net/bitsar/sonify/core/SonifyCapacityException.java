package net.bitsar.sonify.core;

/** Thrown when a new client would exceed {@code sonify.max-clients}. */
public class SonifyCapacityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SonifyCapacityException(int maxClients) {
        super("JVM Sonify is already serving its maximum of " + maxClients + " clients");
    }
}
