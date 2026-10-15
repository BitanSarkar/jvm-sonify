package net.bitsar.sonify.core;

/** A point-in-time view of the broker, surfaced by the optional Actuator endpoint. */
public record SonifyStatus(boolean running,
                           boolean jfrAvailable,
                           int clients,
                           long eventsPublished,
                           long eventsDropped) {
}
