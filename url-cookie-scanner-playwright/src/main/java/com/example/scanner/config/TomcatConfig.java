package com.example.scanner.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TomcatConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> {
            factory.addConnectorCustomizers(new TomcatConnectorCustomizer() {
                @Override
                public void customize(Connector connector) {

                    // Get protocol handler
                    if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol) {
                        AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();

                        // 🔥 CRITICAL: Set VERY LARGE header sizes (100MB)
                        protocol.setMaxHttpHeaderSize(1999999999); // 100MB
                        protocol.setMaxHttpRequestHeaderSize(1999999999); // 100MB

                        // Connection settings
                        protocol.setConnectionTimeout(120000); // 2 minutes
                        protocol.setKeepAliveTimeout(120000);
                        protocol.setMaxKeepAliveRequests(1000);

                        // Thread settings
                        protocol.setMaxThreads(200);
                        protocol.setMinSpareThreads(25);
                    }

                    // 🔥 Set directly on connector as well
                    connector.setProperty("maxHttpHeaderSize", "104857600"); // 100MB
                    connector.setProperty("maxHttpRequestHeaderSize", "104857600"); // 100MB

                    // POST size limits
                    connector.setMaxPostSize(104857600); // 100MB
                    connector.setMaxSavePostSize(104857600); // 100MB

                    // Additional connector properties
                    connector.setProperty("maxConnections", "10000");
                    connector.setProperty("acceptCount", "1000");
                    connector.setProperty("compression", "off");
                    connector.setProperty("compressionMinSize", "2048");
                    connector.setProperty("socketBuffer", "131072"); // 128KB buffer
                }
            });
        };
    }
}