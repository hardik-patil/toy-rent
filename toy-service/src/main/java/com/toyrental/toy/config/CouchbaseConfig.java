package com.toyrental.toy.config;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.codec.JacksonJsonSerializer;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.tracing.opentelemetry.OpenTelemetryRequestTracer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.opentelemetry.api.OpenTelemetry;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class CouchbaseConfig {

    /**
     * The Couchbase SDK's default JSON (de)serializer is backed by its own isolated Jackson
     * ObjectMapper — separate from Spring's, and without java.time support. Every document here
     * has LocalDate/Instant fields (blockedDates, nextAvailable, currentDate, lastUpdated, ...),
     * so without this, writes throw EncodingFailureException and reads throw
     * DecodingFailureException, both wrapping Jackson's "Java 8 date/time type ... not supported
     * by default". Caught earlier only because LogicalDateService's read path swallows the
     * failure and falls back to LocalDate.now() — silently defeating the "never call
     * LocalDate.now() directly" rule this service otherwise follows.
     */
    @Bean(destroyMethod = "shutdown")
    public ClusterEnvironment couchbaseClusterEnvironment(OpenTelemetry openTelemetry) {
        ObjectMapper couchbaseObjectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return ClusterEnvironment.builder()
                .jsonSerializer(JacksonJsonSerializer.create(couchbaseObjectMapper))
                .requestTracer(OpenTelemetryRequestTracer.wrap(openTelemetry))
                .build();
    }

    @Bean(destroyMethod = "disconnect")
    public Cluster couchbaseCluster(CouchbaseProperties props, ClusterEnvironment environment) {
        return Cluster.connect(props.getConnectionString(),
                ClusterOptions.clusterOptions(props.getUsername(), props.getPassword()).environment(environment));
    }

    @Bean(name = "availabilityBucket")
    public Bucket availabilityBucket(Cluster cluster, CouchbaseProperties props) {
        return openBucket(cluster, props.getBucket().getAvailability());
    }

    @Bean(name = "logicalDateBucket")
    public Bucket logicalDateBucket(Cluster cluster, CouchbaseProperties props) {
        return openBucket(cluster, props.getBucket().getLogicalDate());
    }

    private Bucket openBucket(Cluster cluster, String bucketName) {
        Bucket bucket = cluster.bucket(bucketName);
        try {
            bucket.waitUntilReady(Duration.ofSeconds(10));
            log.info("Connected to Couchbase bucket={}", bucketName);
        } catch (RuntimeException e) {
            // cluster.bucket(name) above never blocks — only waitUntilReady does — so it's
            // safe to swallow this and return the (not-yet-verified) bucket reference rather
            // than fail application startup entirely. Every caller of this bucket already
            // has a documented fallback for Couchbase being unavailable (LogicalDateService
            // falls back to the wall clock, AvailabilityService's loadOrDefault() treats a
            // missing/unreachable document as fully available) — but those fallbacks only
            // help if the bean can be created in the first place. Confirmed live: without
            // this, Couchbase being down took the whole service down with it instead of
            // degrading, directly contradicting CLAUDE.md's stated fallback design.
            log.warn("Couchbase bucket={} not ready within timeout — starting anyway, relying on caller-side fallbacks: {}",
                    bucketName, e.getMessage());
        }
        return bucket;
    }

    @Getter
    @Setter
    @Configuration
    @ConfigurationProperties(prefix = "couchbase")
    public static class CouchbaseProperties {

        private String connectionString;
        private String username;
        private String password;
        private BucketNames bucket = new BucketNames();

        @Getter
        @Setter
        public static class BucketNames {
            private String availability;
            private String logicalDate;
        }
    }

}
