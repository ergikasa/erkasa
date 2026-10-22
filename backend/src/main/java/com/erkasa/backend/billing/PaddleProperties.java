package com.erkasa.backend.billing;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "erkasa.paddle" in application.yml (phase 8).
 *
 * @param environment        sandbox (test cards, no real money) or production
 * @param clientToken        public token for Paddle.js in the browser (test_... in sandbox, live_... in production)
 * @param webhookSecret      the secret of the webhook destination (pdl_ntfset_...): proves a webhook came from Paddle
 * @param signatureTolerance how old a webhook may be (replays are also stopped by the unique transaction id)
 * @param packs              what can be bought; a pack without priceId is hidden
 */
@ConfigurationProperties(prefix = "erkasa.paddle")
public record PaddleProperties(String environment,
                               String clientToken,
                               String webhookSecret,
                               Duration signatureTolerance,
                               List<Pack> packs) {

    /**
     * @param id           starter / creator / pro
     * @param priceId      pri_... from the Paddle dashboard (Catalog -> Products -> Prices)
     * @param credits      what the pack gives (1 credit = 1 second of video)
     * @param displayPrice what the app shows, e.g. "$5" (Paddle shows the exact local price + tax at checkout)
     */
    public record Pack(String id, String priceId, int credits, String displayPrice) {
    }

    public PaddleProperties {
        environment = "production".equalsIgnoreCase(environment) ? "production" : "sandbox";
        clientToken = clientToken == null ? "" : clientToken.trim();
        webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        signatureTolerance = signatureTolerance == null ? Duration.ofMinutes(5) : signatureTolerance;
        packs = packs == null ? List.of() : List.copyOf(packs);
    }

    /** Packs that can really be sold (have a Paddle price). */
    public List<Pack> sellable() {
        return packs.stream().filter(p -> p.priceId() != null && !p.priceId().isBlank() && p.credits() > 0).toList();
    }

    public boolean configured() {
        return !clientToken.isBlank() && !sellable().isEmpty();
    }
}
