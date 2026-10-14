package com.erkasa.backend.assets;


import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Adds "storage" to /actuator/health: UP when the bucket can be reached, DOWN otherwise.
 * The bean name "storageHealthIndicator" becomes the component name "storage".
 */
@Component("storageHealthIndicator")
public class StorageHealthIndicator implements HealthIndicator {

    private final S3Client s3;
    private final StorageProperties props;

    public StorageHealthIndicator(S3Client s3, StorageProperties props) {
        this.s3 = s3;
        this.props = props;
    }

    @Override
    public Health health() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
            return Health.up()
                    .withDetail("bucket", props.bucket())
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("bucket", props.bucket())
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}