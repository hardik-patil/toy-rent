package com.toyrental.toy.config;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
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

    @Bean
    public Cluster couchbaseCluster(CouchbaseProperties props) {
        return Cluster.connect(props.getConnectionString(), props.getUsername(), props.getPassword());
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
        bucket.waitUntilReady(Duration.ofSeconds(10));
        log.info("Connected to Couchbase bucket={}", bucketName);
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
