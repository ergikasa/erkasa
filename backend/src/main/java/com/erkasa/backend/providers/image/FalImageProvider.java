package com.erkasa.backend.providers.image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.ProviderHttp;
import com.erkasa.backend.providers.ProviderUsage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Scene images through fal.ai. Active when erkasa.providers.image=fal.
 *
 * Two HTTP calls per image:
 *   1. POST https://fal.run/{model}  -> JSON with the URL of the finished image
 *   2. GET that URL                  -> the image bytes
 *
 * The model is only a config value (default Nano Banana 2), so switching to another fal
 * model that takes prompt + aspect_ratio needs no code change.
 *
 * Docs: https://fal.ai/models/fal-ai/nano-banana-2/api
 */
@Component
@ConditionalOnProperty(prefix = "erkasa.providers", name = "image", havingValue = "fal")
@EnableConfigurationProperties(FalProperties.class)
public class FalImageProvider implements ImageProvider {

    static final String NAME = "fal";

    private final FalProperties props;
    private final RestClient client;

    public FalImageProvider(FalProperties props, ProviderHttp http) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "erkasa.providers.image=fal but FAL_KEY is not set (see infra/.env)");
        }
        this.props = props;
        this.client = http.client(props.baseUrl(), props.timeout());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String model() {
        return props.model();
    }

    @Override
    public GeneratedImage generate(ImageRequest request) {
        FalResponse response = send(body(request));

        if (response == null || response.images() == null || response.images().isEmpty()
                || response.images().get(0).url() == null) {
            // The model answered but drew nothing: almost always its safety filter.
            // Retrying the same prompt gives the same result, so not retryable.
            String why = response == null || response.description() == null ? "" : response.description();
            throw new ProviderException(NAME, "NO_IMAGE", "No image returned. " + shorten(why), false, null);
        }

        FalImage first = response.images().get(0);
        byte[] data = download(first.url());
        String contentType = first.content_type() != null ? first.content_type() : "image/" + props.outputFormat();
        int[] size = dimensions(data, first);

        ProviderUsage usage = new ProviderUsage(props.model(), "images", 0, 1, props.usdPerImage());
        return new GeneratedImage(data, contentType, size[0], size[1], usage);
    }

    private Map<String, Object> body(ImageRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", request.prompt());
        body.put("num_images", 1);
        body.put("aspect_ratio", request.aspectRatio().ratio());
        body.put("resolution", props.resolution());
        body.put("output_format", props.outputFormat());
        body.put("safety_tolerance", props.safetyTolerance());
        return body;
    }

    private FalResponse send(Map<String, Object> body) {
        try {
            return client.post()
                    .uri("/" + props.model())
                    .header("Authorization", "Key " + props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String errorBody = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw ProviderException.fromHttpStatus(NAME, res.getStatusCode().value(), errorBody);
                    })
                    .body(FalResponse.class);
        } catch (ResourceAccessException e) {
            throw new ProviderException(NAME, "TIMEOUT", e.getMessage(), true, e);
        }
    }

    /** Fetches the finished image. fal can also answer with a data: URI, so both are handled. */
    private byte[] download(String url) {
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            return Base64.getDecoder().decode(url.substring(comma + 1));
        }
        try {
            byte[] data = client.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        // The image exists on fal's side; a failed download is worth another try.
                        throw new ProviderException(NAME, "DOWNLOAD_FAILED",
                                "HTTP " + res.getStatusCode().value() + " downloading the image", true, null);
                    })
                    .body(byte[].class);
            if (data == null || data.length == 0) {
                throw new ProviderException(NAME, "DOWNLOAD_FAILED", "Empty image file", true, null);
            }
            return data;
        } catch (ResourceAccessException e) {
            throw new ProviderException(NAME, "DOWNLOAD_FAILED", e.getMessage(), true, e);
        }
    }

    /** Width and height: from fal's answer if present, otherwise read from the file header. */
    private static int[] dimensions(byte[] data, FalImage image) {
        if (image.width() != null && image.height() != null) {
            return new int[] {image.width(), image.height()};
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers != null && readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in);
                    return new int[] {reader.getWidth(0), reader.getHeight(0)};
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException e) {
            // Unknown size is not a reason to throw away a paid image.
        }
        return new int[] {0, 0};
    }

    private static String shorten(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    // ---------- the parts of fal's answer we read (other fields are ignored) ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FalResponse(List<FalImage> images, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FalImage(String url, String content_type, Integer width, Integer height) {
    }
}