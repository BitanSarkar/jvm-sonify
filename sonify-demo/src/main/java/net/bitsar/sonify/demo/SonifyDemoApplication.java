package net.bitsar.sonify.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Demo application whose endpoints provoke GC, contention and heap growth on demand. */
@SpringBootApplication
public class SonifyDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SonifyDemoApplication.class, args);
    }
}
