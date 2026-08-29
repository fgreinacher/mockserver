package org.mockserver.examples.proxy.servicebackend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import jakarta.annotation.Resource;

/**
 * This configuration contains top level beans and any configuration required by filters (as WebMvcConfiguration only loaded within Dispatcher Servlet)
 *
 * @author jamesdbloom
 */
@Configuration
@Profile("backend")
@PropertySource({"classpath:application.properties"})
public class BackEndServiceConfiguration {

    @Resource
    private Environment environment;

    @Bean
    public BookServer bookServer() {
        // Bind to an ephemeral port (0). BookServer.startServer() binds it, reads back the
        // actual OS-assigned port and publishes it as the "bookService.port" system property
        // that the HTTP client beans read. This removes the find-a-free-port-then-bind-it-later
        // (TOCTOU) race that could leave the backend unbound and surface as an intermittent
        // "502 Bad Gateway" from the proxy when it could not reach this backend.
        return new BookServer(0, false);
    }

}
