package net.bitsar.sonify.autoconfigure;

import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the browser UI under the configured base path, in both MVC and WebFlux.
 *
 * <p>Served explicitly rather than relying on Boot's default static resource locations, so that
 * changing {@code sonify.base-path} actually moves the UI. Only the files listed here can be
 * requested, which keeps a path such as {@code ../../application.properties} from resolving.
 */
@RestController
public class SonifyUiController {

    private static final String ROOT = "META-INF/resources/sonify/";

    private static final Map<String, MediaType> ASSETS = Map.of(
            "index.html", MediaType.TEXT_HTML,
            "sonify.css", MediaType.valueOf("text/css"),
            "sonify.js", MediaType.valueOf("text/javascript"),
            "viz.js", MediaType.valueOf("text/javascript"),
            "audio.js", MediaType.valueOf("text/javascript"));

    private final String basePath;

    public SonifyUiController(@Value("${sonify.base-path:/sonify}") String basePath) {
        this.basePath = basePath;
    }

    /**
     * Redirects to the trailing-slash form rather than serving the page here.
     *
     * <p>The page asks for its stream with the relative URL "stream". Served at {@code /sonify/}
     * that resolves to {@code /sonify/stream}; served at {@code /sonify} it resolves to
     * {@code /stream}, which does not exist. The page would load and then sit disconnected
     * forever, so the slash is made certain before any of its scripts run.
     */
    @GetMapping("${sonify.base-path:/sonify}")
    public ResponseEntity<Resource> index() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(basePath + "/"))
                .build();
    }

    @GetMapping("${sonify.base-path:/sonify}/")
    public ResponseEntity<Resource> indexWithSlash() {
        return asset("index.html");
    }

    @GetMapping("${sonify.base-path:/sonify}/{file}")
    public ResponseEntity<Resource> file(@PathVariable("file") String file) {
        return asset(file);
    }

    private ResponseEntity<Resource> asset(String file) {
        MediaType contentType = ASSETS.get(file);
        if (contentType == null) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new ClassPathResource(ROOT + file);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noCache())
                .body(resource);
    }
}
