package com.erkasa.backend.providers.image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.ProviderHttp;
import com.erkasa.backend.providers.ProviderUsage;

/**
 * Scene images through Clipdrop. Active when erkasa.providers.image=clipdrop.
 *
 *   1. POST /text-to-image/v1  (prompt)            -> 1024x1024 PNG        1 credit
 *   2. POST /uncrop/v1         (image + extend_*)  -> 9:16 or 16:9 JPEG    1 credit
 *      (skipped for SQUARE, or when uncrop=false)
 *
 * Docs: https://clipdrop.co/apis/docs/text-to-image , https://clipdrop.co/apis/docs/uncrop
 */
@Component
@ConditionalOnProperty(prefix = "erkasa.providers", name = "image", havingValue = "clipdrop")
@EnableConfigurationProperties(ClipdropProperties.class)
public class ClipdropImageProvider implements ImageProvider {

    static final String NAME = "clipdrop";
    private static final int MAX_PROMPT = 1000;

    private final ClipdropProperties props;
    private final RestClient client;

    public ClipdropImageProvider(ClipdropProperties props, ProviderHttp http) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "erkasa.providers.image=clipdrop but CLIPDROP_API_KEY is not set (see infra/.env)");
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
        return props.uncrop() ? "text-to-image-v1+uncrop" : "text-to-image-v1";
    }

    @Override
    public GeneratedImage generate(ImageRequest request) {
        String prompt = request.prompt() == null ? "" : request.prompt().strip();
        if (prompt.length() > MAX_PROMPT) {
            prompt = prompt.substring(0, MAX_PROMPT);
        }

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("prompt", prompt);
        Result square = post("/text-to-image/v1", form);
        long credits = square.credits();

        byte[] data = square.data();
        String contentType = "image/png";
        AspectRatio ratio = request.aspectRatio();

        if (props.uncrop() && ratio != AspectRatio.SQUARE) {
            int side = size(data)[0];
            if (side <= 0) {
                side = 1024;
            }
            // Long side = side * 16/9; split the extra pixels evenly on both ends.
            int extra = Math.round(side * 16f / 9f) - side;
            int first = extra / 2;
            int second = extra - first;

            MultiValueMap<String, Object> uncrop = new LinkedMultiValueMap<>();
            uncrop.add("image_file", new ByteArrayResource(data) {
                @Override
                public String getFilename() {
                    return "scene.png";
                }
            });
            if (ratio == AspectRatio.PORTRAIT) {
                uncrop.add("extend_up", String.valueOf(first));
                uncrop.add("extend_down", String.valueOf(second));
            } else {
                uncrop.add("extend_left", String.valueOf(first));
                uncrop.add("extend_right", String.valueOf(second));
            }
            Result extended = post("/uncrop/v1", uncrop);
            credits += extended.credits();
            data = extended.data();
            contentType = extended.contentType();
        }

        int[] wh = size(data);
        BigDecimal cost = props.usdPerCredit().multiply(BigDecimal.valueOf(credits));
        ProviderUsage usage = new ProviderUsage(model(), "credits", 0, credits, cost);
        return new GeneratedImage(data, contentType, wh[0], wh[1], usage);
    }

    private record Result(byte[] data, String contentType, long credits) {
    }

    private Result post(String path, MultiValueMap<String, Object> form) {
        ResponseEntity<byte[]> response;
        try {
            response = client.post()
                    .uri(path)
                    .header("x-api-key", props.apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        int status = res.getStatusCode().value();
                        String errorBody = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (status == 402) {
                            throw new ProviderException(NAME, "NO_CREDITS",
                                    "Clipdrop credits are used up: " + errorBody, false, null);
                        }
                        throw ProviderException.fromHttpStatus(NAME, status, errorBody);
                    })
                    .toEntity(byte[].class);
        } catch (ResourceAccessException e) {
            throw new ProviderException(NAME, "TIMEOUT", e.getMessage(), true, e);
        }

        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new ProviderException(NAME, "EMPTY_RESPONSE", "No image in the answer of " + path, true, null);
        }
        MediaType type = response.getHeaders().getContentType();
        String contentType = type == null ? "image/png" : type.getType() + "/" + type.getSubtype();
        return new Result(body, contentType, credits(response.getHeaders().getFirst("x-credits-consumed")));
    }

    /** Credits used by one call, from the response header; 1 if missing. */
    private static long credits(String header) {
        try {
            return header == null ? 1 : Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Width and height read from the file header; {0, 0} if unknown. */
    static int[] size(byte[] data) {
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
            // unknown size
        }
        return new int[] {0, 0};
    }
}