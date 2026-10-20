package com.erkasa.backend.assets;

import java.time.Duration;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Reads and writes the files a video is made of (scene images, voice-overs, later the MP4).
 * SeaweedFS locally, Cloudflare R2 in production: same code, only the endpoint changes.
 *
 * Keys look like: projects/{projectId}/{jobId}/scene-0.png
 */
@Service
public class AssetStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties props;

    public AssetStorage(S3Client s3, S3Presigner presigner, StorageProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
    }

    /** A link anyone can GET for `valid`, without credentials (used by the render worker). */
    public String presignGet(String key, Duration valid) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(valid)
                .getObjectRequest(GetObjectRequest.builder().bucket(props.bucket()).key(key).build())
                .build()).url().toString();
    }

    /**
     * A link to upload one file with PUT for `valid`. The upload must send exactly this
     * Content-Type header, because it is part of the signature.
     */
    public String presignPut(String key, String contentType, Duration valid) {
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(valid)
                .putObjectRequest(PutObjectRequest.builder().bucket(props.bucket()).key(key).contentType(contentType).build())
                .build()).url().toString();
    }

    public void put(String key, byte[] data, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(props.bucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) data.length)
                        .build(),
                RequestBody.fromBytes(data));
    }

    /** The file's bytes, or null if there is no file with this key. */
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(key)
                    .build()).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    /** File extension for a content type: image/png -> png, audio/mpeg -> mp3. */
    public static String extension(String contentType) {
        if (contentType == null) {
            return "bin";
        }
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            case "video/mp4" -> "mp4";
            default -> "bin";
        };
    }

    /** The reverse of extension(), for serving files back. */
    public static String contentTypeOf(String key) {
        String k = key.toLowerCase();
        if (k.endsWith(".png")) {
            return "image/png";
        }
        if (k.endsWith(".jpg") || k.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (k.endsWith(".webp")) {
            return "image/webp";
        }
        if (k.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (k.endsWith(".wav")) {
            return "audio/wav";
        }
        if (k.endsWith(".mp4")) {
            return "video/mp4";
        }
        return "application/octet-stream";
    }
}
