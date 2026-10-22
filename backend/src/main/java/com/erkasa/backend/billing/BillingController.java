package com.erkasa.backend.billing;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 *   GET  /api/billing/packs           what can be bought + the Paddle.js settings (logged in)
 *   POST /api/billing/paddle/webhook  Paddle tells us about payments (public, signed)
 */
@RestController
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    private final PaddleProperties props;
    private final BillingService billing;
    private final ObjectMapper json;
    private final Clock clock;

    public BillingController(PaddleProperties props, BillingService billing, ObjectMapper json) {
        this.props = props;
        this.billing = billing;
        this.json = json;
        this.clock = Clock.systemUTC();
    }

    public record PackView(String id, int credits, String displayPrice, String priceId) {
    }

    /** enabled = false until the Paddle keys and prices are set (the app then hides "Buy"). */
    public record PacksResponse(boolean enabled, String environment, String clientToken, List<PackView> packs) {
    }

    @GetMapping("/api/billing/packs")
    public PacksResponse packs() {
        return new PacksResponse(props.configured(), props.environment(), props.clientToken(),
                props.sellable().stream().map(p -> new PackView(p.id(), p.credits(), p.displayPrice(), p.priceId())).toList());
    }

    /**
     * The body is read as a String: the signature is over the exact bytes Paddle sent.
     * 401 for a bad signature (Paddle will retry, so a wrong secret shows up in the dashboard);
     * 200 for everything we accept, even events we ignore, so Paddle stops retrying them.
     */
    @PostMapping("/api/billing/paddle/webhook")
    public ResponseEntity<Void> webhook(@RequestBody String body,
                                        @RequestHeader(value = "Paddle-Signature", required = false) String signature) {
        if (!PaddleSignature.isValid(signature, body, props.webhookSecret(), props.signatureTolerance(), clock)) {
            log.warn("Paddle webhook with a bad or missing signature rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode event = json.readTree(body);
        BillingService.Outcome outcome = billing.handle(event);
        log.info("Paddle webhook {} {}: {}", event.path("event_type").asString(""), event.path("event_id").asString(""), outcome);
        return ResponseEntity.ok().build();
    }
}
