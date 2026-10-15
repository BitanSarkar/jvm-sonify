package net.bitsar.sonify.autoconfigure;

import net.bitsar.sonify.core.SonifyBroker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Serves the stream over Spring MVC when the application is a servlet application. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(SseEmitter.class)
public class SonifyMvcConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SonifyMvcController sonifyMvcController(SonifyBroker broker) {
        return new SonifyMvcController(broker);
    }
}
