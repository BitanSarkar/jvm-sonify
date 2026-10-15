package net.bitsar.sonify.autoconfigure;

import net.bitsar.sonify.core.SonifyBroker;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Registers JVM Sonify when {@code sonify.enabled=true}.
 *
 * <p>Without that property nothing here is created, so the starter is inert on the classpath.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "sonify", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SonifyProperties.class)
@Import({SonifyMvcConfiguration.class, SonifyWebFluxConfiguration.class, SonifyActuatorConfiguration.class})
public class SonifyAutoConfiguration {

    /**
     * The broker is destroyed with the context, which stops the recording stream and the tick
     * thread; {@code destroyMethod} is inferred from {@link SonifyBroker#close()}.
     */
    @Bean
    @ConditionalOnMissingBean
    public SonifyBroker sonifyBroker(SonifyProperties properties) {
        return new SonifyBroker(properties.toConfig());
    }

    @Bean
    @ConditionalOnMissingBean
    public SonifySecurityAdvisor sonifySecurityAdvisor(SonifyProperties properties) {
        return new SonifySecurityAdvisor(properties);
    }

    /** The UI is plain static content, so one controller covers both transports. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    public SonifyUiController sonifyUiController() {
        return new SonifyUiController();
    }
}
