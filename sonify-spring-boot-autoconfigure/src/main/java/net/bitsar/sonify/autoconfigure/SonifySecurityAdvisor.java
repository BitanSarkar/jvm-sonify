package net.bitsar.sonify.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.ClassUtils;

/**
 * Says something once, at startup, about who can reach the stream.
 *
 * <p>Deliberately changes no security configuration. The endpoint exposes thread names, monitor
 * class names and heap figures, so whether it should be public is the application's decision, not
 * this library's; silently permitting it would be the wrong default. When Spring Security is
 * present this logs the snippet needed to allow it through.
 */
public class SonifySecurityAdvisor implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(SonifySecurityAdvisor.class);
    private static final String SECURITY_MARKER = "org.springframework.security.web.SecurityFilterChain";

    private final SonifyProperties properties;

    public SonifySecurityAdvisor(SonifyProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        String basePath = properties.getBasePath();
        if (ClassUtils.isPresent(SECURITY_MARKER, getClass().getClassLoader())) {
            LOG.info("JVM Sonify is enabled at {}/ and Spring Security is on the classpath, so your "
                    + "existing rules apply unchanged. To open it explicitly, permit \"{}/**\".",
                    basePath, basePath);
        } else {
            LOG.info("JVM Sonify is enabled at {}/ with no Spring Security on the classpath, so it is "
                    + "reachable by anyone who can reach this application. It reports thread names, "
                    + "monitor classes and heap figures.", basePath);
        }
    }
}
