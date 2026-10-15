package net.bitsar.sonify.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints that provoke the behaviour the monitor is meant to show. */
@RestController
@RequestMapping("/load")
public class LoadController {

    private final LoadLab lab;

    public LoadController(LoadLab lab) {
        this.lab = lab;
    }

    @GetMapping("/alloc")
    public String alloc(@RequestParam(name = "mb", defaultValue = "50") int mb) {
        return lab.allocate(mb);
    }

    @GetMapping("/lock")
    public String lock(@RequestParam(name = "threads", defaultValue = "16") int threads,
                       @RequestParam(name = "ms", defaultValue = "50") long ms) {
        return lab.contend(threads, ms);
    }

    @GetMapping("/leak")
    public String leak(@RequestParam(name = "mb", defaultValue = "10") int mb) {
        return lab.leak(mb);
    }

    @GetMapping("/calm")
    public String calm() {
        return lab.calm();
    }

    @GetMapping("/status")
    public String status() {
        return lab.status();
    }
}
