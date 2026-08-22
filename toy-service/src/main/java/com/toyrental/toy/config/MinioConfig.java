package com.toyrental.toy.config;

import io.minio.MinioClient;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }

    @Getter
    @Setter
    @Configuration
    @ConfigurationProperties(prefix = "minio")
    public static class MinioProperties {
        private String endpoint;
        // The endpoint above is used for the actual upload connection (in-cluster DNS in
        // K8s, e.g. minio.infra.svc.cluster.local:9000 — not reachable from a browser on the
        // developer's own machine). publicEndpoint is what gets baked into the URL stored on
        // each ToyImage row, so <img src> tags resolve from wherever the frontend is actually
        // running (defaults to localhost:9000, matching this project's established pattern of
        // port-forwarding infra services to localhost for local development).
        private String publicEndpoint;
        private String accessKey;
        private String secretKey;
        private String bucket;
    }

}
