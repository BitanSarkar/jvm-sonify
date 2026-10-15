package net.bitsar.sonify.autoconfigure.it;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal host application for the stream tests.
 *
 * <p>Deliberately not {@code @SpringBootApplication}: component scanning would find the
 * controllers directly and register them whatever the conditions say, which would defeat the
 * point of testing that auto-configuration picks the right transport.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestApplication {
}
