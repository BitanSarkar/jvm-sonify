package net.bitsar.sonify.demo.reactive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * The same scenarios on Netty rather than Tomcat.
 *
 * <p>Run both at once, each with its own Sonify page, and drive identical load at them to see
 * what the concurrency model actually changes.
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "net.bitsar.sonify.demo.reactive",
        "net.bitsar.sonify.scenarios"})
public class SonifyReactiveApplication {

    public static void main(String[] args) {
        SpringApplication.run(SonifyReactiveApplication.class, args);
    }
}
