package com.toyrental.booking.config;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.codec.JacksonJsonSerializer;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
     * The Couchbase SDK's default JSON (de)serializer has its own isolated Jackson ObjectMapper
     * without java.time support — registering JavaTimeModule here from the start, rather than
     * finding it the hard way like toy-service's Sprint 2 EncodingFailureException/
     * DecodingFailureException bug (see toy-service's CouchbaseConfig for the full story).
     */
    @Bean(destroyMethod = "shutdown")
    public ClusterEnvironment couchbaseClusterEnvironment() {
        ObjectMapper couchbaseObjectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return ClusterEnvironment.builder()
                .jsonSerializer(JacksonJsonSerializer.create(couchbaseObjectMapper))
                .build();
    }

    @Bean(destroyMethod = "disconnect")
    public Cluster couchbaseCluster(CouchbaseProperties props, ClusterEnvironment environment) {
        return Cluster.connect(props.getConnectionString(),
                ClusterOptions.clusterOptions(props.getUsername(), props.getPassword()).environment(environment));
    }

    @Bean(name = "reportsBucket")
    public Bucket reportsBucket(Cluster cluster, CouchbaseProperties props) {
        Bucket bucket = cluster.bucket(props.getBucket().getReports());
        bucket.waitUntilReady(Duration.ofSeconds(10));
        log.info("Connected to Couchbase bucket={}", props.getBucket().getReports());
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
            private String reports;
        }
    }

}
