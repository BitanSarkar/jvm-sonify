package net.bitsar.sonify.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import net.bitsar.sonify.core.SonifyBroker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class SonifyAutoConfigurationTest {

    private static final AutoConfigurations SONIFY =
            AutoConfigurations.of(SonifyAutoConfiguration.class);

    @Test
    @DisplayName("the starter is inert on the classpath: nothing is registered by default")
    void disabledByDefault() {
        new WebApplicationContextRunner()
                .withConfiguration(SONIFY)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SonifyBroker.class)
                        .doesNotHaveBean(SonifyProperties.class)
                        .doesNotHaveBean(SonifyMvcController.class)
                        .doesNotHaveBean(SonifyUiController.class)
                        .doesNotHaveBean(SonifySecurityAdvisor.class));
    }

    @Test
    @DisplayName("sonify.enabled=false is also inert")
    void explicitlyDisabled() {
        new WebApplicationContextRunner()
                .withConfiguration(SONIFY)
                .withPropertyValues("sonify.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SonifyBroker.class));
    }

    @Test
    @DisplayName("a servlet application gets the MVC transport and not the reactive one")
    void servletTransport() {
        new WebApplicationContextRunner()
                .withConfiguration(SONIFY)
                .withPropertyValues("sonify.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(SonifyBroker.class)
                        .hasSingleBean(SonifyMvcController.class)
                        .hasSingleBean(SonifyUiController.class)
                        .hasSingleBean(SonifySecurityAdvisor.class)
                        .doesNotHaveBean(SonifyWebFluxController.class));
    }

    @Test
    @DisplayName("a reactive application gets the WebFlux transport and not the servlet one")
    void reactiveTransport() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(SONIFY)
                .withPropertyValues("sonify.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(SonifyBroker.class)
                        .hasSingleBean(SonifyWebFluxController.class)
                        .hasSingleBean(SonifyUiController.class)
                        .doesNotHaveBean(SonifyMvcController.class));
    }

    @Test
    @DisplayName("a non-web application registers the broker but no endpoints")
    void nonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(SONIFY)
                .withPropertyValues("sonify.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(SonifyBroker.class)
                        .doesNotHaveBean(SonifyUiController.class)
                        .doesNotHaveBean(SonifyMvcController.class)
                        .doesNotHaveBean(SonifyWebFluxController.class));
    }

    @Test
    @DisplayName("properties reach the core configuration")
    void bindsProperties() {
        new WebApplicationContextRunner()
                .withConfiguration(SONIFY)
                .withPropertyValues(
                        "sonify.enabled=true",
                        "sonify.tick=250ms",
                        "sonify.max-clients=3",
                        "sonify.buffer-capacity=64",
                        "sonify.max-thread-slices=4",
                        "sonify.grace-period=5s",
                        "sonify.events.contention-threshold=25ms",
                        "sonify.events.park-threshold=75ms",
                        "sonify.events.allocation-window=4s")
                .run(context -> {
                    SonifyBroker broker = context.getBean(SonifyBroker.class);
                    assertThat(broker.config().tick()).isEqualTo(Duration.ofMillis(250));
                    assertThat(broker.config().maxClients()).isEqualTo(3);
                    assertThat(broker.config().bufferCapacity()).isEqualTo(64);
                    assertThat(broker.config().maxThreadSlices()).isEqualTo(4);
                    assertThat(broker.config().gracePeriod()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(broker.config().contentionThreshold()).isEqualTo(Duration.ofMillis(25));
                    assertThat(broker.config().parkThreshold()).isEqualTo(Duration.ofMillis(75));
                    assertThat(broker.config().allocationWindow()).isEqualTo(Duration.ofSeconds(4));
                });
    }

    @Test
    @DisplayName("an application can supply its own broker")
    void respectsUserSuppliedBroker() {
        new WebApplicationContextRunner()
                .withConfiguration(SONIFY)
                .withPropertyValues("sonify.enabled=true")
                .withBean("myBroker", SonifyBroker.class,
                        () -> new SonifyBroker(net.bitsar.sonify.core.SonifyConfig.defaults()))
                .run(context -> assertThat(context)
                        .hasSingleBean(SonifyBroker.class)
                        .hasBean("myBroker"));
    }

    @Test
    @DisplayName("closing the context stops the pipeline rather than leaving it measuring")
    void shutsDownWithTheContext() {
        SonifyBroker[] captured = new SonifyBroker[1];
        new WebApplicationContextRunner()
                .withConfiguration(SONIFY)
                .withPropertyValues("sonify.enabled=true", "sonify.grace-period=1ms")
                .run(context -> {
                    captured[0] = context.getBean(SonifyBroker.class);
                    captured[0].subscribe();
                    assertThat(captured[0].status().running()).isTrue();
                });

        assertThat(captured[0].status().running())
                .as("the broker must stop when its context closes")
                .isFalse();
    }
}
