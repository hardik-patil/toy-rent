package com.toyrental.booking.service;

import com.toyrental.booking.config.MinioConfig.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** Stores/retrieves month-end report PDFs at reports/{year}/{month}/monthly-report-{year}-{month}.pdf. */
@Slf4j
@Service
public class MinioService {

    private final MinioClient minioClient;
    private final MinioProperties props;

    public MinioService(MinioClient minioClient, MinioProperties props) {
        this.minioClient = minioClient;
        this.props = props;
    }

    public String uploadReport(byte[] pdfBytes, int month, int year) {
        String objectPath = String.format("reports/%04d/%02d/monthly-report-%04d-%02d.pdf", year, month, year, month);
        try {
            ensureBucketExists();
            try (InputStream in = new ByteArrayInputStream(pdfBytes)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(props.getBucket())
                        .object(objectPath)
                        .stream(in, pdfBytes.length, -1)
                        .contentType("application/pdf")
                        .build());
            }
            log.info("Uploaded report PDF to MinIO bucket={} object={}", props.getBucket(), objectPath);
            return objectPath;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upload report PDF to MinIO at " + objectPath, e);
        }
    }

    public byte[] download(String objectPath) {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(props.getBucket())
                .object(objectPath)
                .build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to download report PDF from MinIO at " + objectPath, e);
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(props.getBucket()).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(props.getBucket()).build());
            log.info("Created MinIO bucket={}", props.getBucket());
        }
    }

}
