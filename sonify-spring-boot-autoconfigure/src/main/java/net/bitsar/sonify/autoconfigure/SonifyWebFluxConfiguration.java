package net.bitsar.sonify.autoconfigure;

import net.bitsar.sonify.core.SonifyBroker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerSentEvent;

/** Serves the stream over WebFlux when the application is a reactive application. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(ServerSentEvent.class)
public class SonifyWebFluxConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SonifyWebFluxController sonifyWebFluxController(SonifyBroker broker, SonifyProperties properties) {
        return new SonifyWebFluxController(broker, properties.getTick());
    }
}
