package com.erkasa.backend.assets;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Local development only: creates the bucket on startup if it does not exist yet.
 * In production the bucket is created once by hand in Cloudflare R2.
 */
@Component
@Profile("dev")
public class DevBucketInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevBucketInitializer.class);

    private final S3Client s3;
    private final StorageProperties props;

    public DevBucketInitializer(S3Client s3, StorageProperties props) {
        this.s3 = s3;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String bucket = props.bucket();
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Storage bucket '{}' is ready", bucket);
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Storage bucket '{}' created", bucket);
        }
    }
}