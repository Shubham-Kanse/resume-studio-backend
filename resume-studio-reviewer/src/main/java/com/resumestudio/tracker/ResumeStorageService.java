package com.resumestudio.tracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;

@Service
public class ResumeStorageService {

    private static final Logger log = LoggerFactory.getLogger(ResumeStorageService.class);

    private final S3Client s3;
    private final String bucket;

    public ResumeStorageService(
        @Value("${supabase.storage.endpoint}") String endpoint,
        @Value("${supabase.storage.region}") String region,
        @Value("${supabase.storage.bucket}") String bucket,
        @Value("${supabase.storage.access-key}") String accessKey,
        @Value("${supabase.storage.secret-key}") String secretKey
    ) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(true) // required for Supabase S3
            .build();
    }

    /** Uploads resume and returns the S3 key. */
    public String upload(String userId, String jobId, MultipartFile file, String filename) {
        try {
            // Strip any path components — only keep the bare filename
            String safeName = filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            String key = userId + "/" + jobId + "/" + safeName;
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                    .build(),
                RequestBody.fromBytes(file.getBytes())
            );
            return key;
        } catch (Exception e) {
            log.error("S3 upload failed — bucket: {}, key: {}/{}/{}, cause: {}", bucket, userId, jobId, filename, e.getMessage(), e);
            throw new RuntimeException("Failed to upload resume", e);
        }
    }

    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
