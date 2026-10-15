package net.bitsar.sonify.autoconfigure;

import net.bitsar.sonify.core.SonifyBroker;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the read-only {@code sonify} Actuator endpoint when Actuator is present. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({Endpoint.class, ConditionalOnAvailableEndpoint.class})
public class SonifyActuatorConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint(endpoint = SonifyActuatorEndpoint.class)
    public SonifyActuatorEndpoint sonifyActuatorEndpoint(SonifyBroker broker) {
        return new SonifyActuatorEndpoint(broker);
    }
}
