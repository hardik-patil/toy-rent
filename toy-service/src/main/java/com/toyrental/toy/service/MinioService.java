package com.toyrental.toy.service;

import com.toyrental.toy.config.MinioConfig.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

/** Stores toy photos at {toyId}/{uuid}-{original-filename}, one bucket for the whole catalogue. */
@Slf4j
@Service
public class MinioService {

    private final MinioClient minioClient;
    private final MinioProperties props;

    public MinioService(MinioClient minioClient, MinioProperties props) {
        this.minioClient = minioClient;
        this.props = props;
    }

    /** Returns the public URL to store on the ToyImage row, not the internal object path. */
    public String uploadToyImage(String toyId, MultipartFile file) {
        String safeName = file.getOriginalFilename() == null ? "photo" : file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_");
        String objectPath = toyId + "/" + UUID.randomUUID() + "-" + safeName;
        try {
            ensureBucketExists();
            try (InputStream in = file.getInputStream()) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(props.getBucket())
                        .object(objectPath)
                        .stream(in, file.getSize(), -1)
                        .contentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                        .build());
            }
            log.info("Uploaded toy image toyId={} bucket={} object={}", toyId, props.getBucket(), objectPath);
            return props.getPublicEndpoint() + "/" + props.getBucket() + "/" + objectPath;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upload toy image to MinIO at " + objectPath, e);
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(props.getBucket()).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(props.getBucket()).build());
            // Toy photos are catalogue images shown in <img> tags across the whole site —
            // meant to be publicly readable, unlike booking-service's report PDFs (which stay
            // behind an authenticated download endpoint). Anonymous GET/HEAD only; uploads
            // still require the service account credentials configured above.
            minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                    .bucket(props.getBucket())
                    .config(anonymousReadPolicy(props.getBucket()))
                    .build());
            log.info("Created MinIO bucket={} with anonymous-read policy", props.getBucket());
        }
    }

    private String anonymousReadPolicy(String bucket) {
        return "{"
                + "\"Version\":\"2012-10-17\","
                + "\"Statement\":[{"
                + "\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":[\"*\"]},"
                + "\"Action\":[\"s3:GetObject\"],"
                + "\"Resource\":[\"arn:aws:s3:::" + bucket + "/*\"]"
                + "}]"
                + "}";
    }

}
