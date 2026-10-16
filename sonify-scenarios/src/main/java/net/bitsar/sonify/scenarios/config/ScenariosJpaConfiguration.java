package net.bitsar.sonify.scenarios.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Points JPA at this module's entities and repositories.
 *
 * <p>Necessary because Spring has three separate discovery mechanisms and component scanning is
 * only one of them. A host application's {@code @ComponentScan} finds the services here, and
 * finds nothing at all in {@code domain} or {@code repository}: entities are located by
 * {@code @EntityScan} and repository interfaces by {@code @EnableJpaRepositories}, each of which
 * defaults to the main application's own package.
 *
 * <p>Declaring both here, rather than in each application that uses this module, keeps the
 * knowledge next to the packages it describes. A published library would go further and do this
 * through auto-configuration, so a consumer needs no annotations at all.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackages = "net.bitsar.sonify.scenarios.domain")
@EnableJpaRepositories(basePackages = "net.bitsar.sonify.scenarios.repository")
public class ScenariosJpaConfiguration {
}
