package net.bitsar.sonify.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import net.bitsar.sonify.core.SonifyBroker;
import net.bitsar.sonify.core.SonifyStatus;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * Reports whether Sonify is measuring anything, and how well it is keeping up.
 *
 * <p>Read only: the stream itself is a plain controller, because Actuator's web endpoints cannot
 * stream server-sent events cleanly.
 */
@Endpoint(id = "sonify")
public class SonifyActuatorEndpoint {

    private final SonifyBroker broker;

    public SonifyActuatorEndpoint(SonifyBroker broker) {
        this.broker = broker;
    }

    @ReadOperation
    public Map<String, Object> status() {
        SonifyStatus status = broker.status();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("running", status.running());
        report.put("jfrAvailable", status.jfrAvailable());
        report.put("clients", status.clients());
        report.put("eventsPublished", status.eventsPublished());
        report.put("eventsDropped", status.eventsDropped());
        report.put("tickMs", broker.config().tick().toMillis());
        return report;
    }
}
