package net.bitsar.sonify.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/** Demo application whose endpoints provoke GC, contention and heap growth on demand. */
@SpringBootApplication
@ComponentScan(basePackages = {"net.bitsar.sonify.demo", "net.bitsar.sonify.scenarios"})
public class SonifyDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SonifyDemoApplication.class, args);
    }
}
