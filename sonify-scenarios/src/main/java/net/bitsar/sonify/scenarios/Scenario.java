package net.bitsar.sonify.scenarios;

/**
 * One reproducible production pathology.
 *
 * <p>Each is something that genuinely happens to real applications, chosen because it leaves a
 * distinct signature in heap, allocation, contention or thread state rather than because it is
 * easy to stage.
 *
 * @param id            stable identifier used in URLs and scripts
 * @param name          short human name
 * @param category      grouping for the catalogue
 * @param situation     the real-world circumstance being reproduced
 * @param expectedGraph what should become visible on the timeline
 * @param expectedSound what should become audible, or that it is deliberately quiet
 * @param transport     which runtime this scenario needs
 */
public record Scenario(String id,
                       String name,
                       Category category,
                       String situation,
                       String expectedGraph,
                       String expectedSound,
                       Transport transport) {

    /** Grouping for the catalogue. */
    public enum Category {
        DATABASE("Database and connection pool"),
        UPSTREAM("Third-party API"),
        CACHE("Redis and caching"),
        CONCURRENCY("Concurrency model");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Which runtime a scenario needs to be meaningful. */
    public enum Transport {
        /** Runs anywhere; the signature does not depend on the server. */
        ANY,
        /** Needs the servlet stack, because the point is thread-per-request behaviour. */
        SERVLET,
        /** Needs the reactive stack, because the point is event-loop behaviour. */
        REACTIVE
    }
}
