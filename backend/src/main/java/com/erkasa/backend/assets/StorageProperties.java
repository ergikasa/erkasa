package com.erkasa.backend.assets;


import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for S3-compatible storage, read from the "erkasa.storage" section of application.yml.
 * Locally this points to SeaweedFS, in production to Cloudflare R2.
 */
@ConfigurationProperties(prefix = "erkasa.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess
) {
}