package com.erkasa.backend.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erkasa.backend.auth.UserRepository;
import com.erkasa.backend.credits.CreditService;

import tools.jackson.databind.JsonNode;

/**
 * Turns Paddle webhooks into credits (phase 8).
 *
 *   transaction.completed  -> the pack's credits are added (once per Paddle transaction)
 *   adjustment.created/updated with action refund or chargeback, status approved
 *                          -> the credits are taken back (never below 0)
 *
 * Paddle retries a webhook until it gets a 2xx, and may send it twice. Everything here is safe
 * to run more than once: the unique transaction id and the payment status make sure of that.
 */
@Service
@EnableConfigurationProperties(PaddleProperties.class)
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final PaddleProperties props;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final CreditService credits;

    public BillingService(PaddleProperties props, PaymentRepository payments, UserRepository users, CreditService credits) {
        this.props = props;
        this.payments = payments;
        this.users = users;
        this.credits = credits;
    }

    /** What happened with one webhook; the controller logs it and answers 200 either way. */
    public enum Outcome { CREDITED, DUPLICATE, REFUNDED, IGNORED }

    @Transactional
    public Outcome handle(JsonNode event) {
        String type = event.path("event_type").asString("");
        JsonNode data = event.path("data");
        return switch (type) {
            case "transaction.completed" -> completed(data);
            case "adjustment.created", "adjustment.updated" -> adjusted(data);
            default -> Outcome.IGNORED;
        };
    }

    private Outcome completed(JsonNode tx) {
        String txnId = tx.path("id").asString("");
        if (txnId.isBlank() || !"completed".equals(tx.path("status").asString(""))) {
            return Outcome.IGNORED;
        }
        if (payments.existsByPaddleTransactionId(txnId)) {
            return Outcome.DUPLICATE;
        }
        // the app puts the user's id into the checkout (Paddle.js customData)
        UUID userId = parseUuid(tx.path("custom_data").path("user_id").asString(""));
        if (userId == null || !users.existsById(userId)) {
            log.error("Paddle {}: no known user_id in custom_data; credits NOT added. Add them by hand (admin).", txnId);
            return Outcome.IGNORED;
        }
        int total = 0;
        String packs = "";
        JsonNode items = tx.path("items");
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            Optional<PaddleProperties.Pack> pack = packFor(item.path("price").path("id").asString(""));
            if (pack.isEmpty()) {
                continue;
            }
            int quantity = Math.max(1, item.path("quantity").asInt(1));
            total += pack.get().credits() * quantity;
            packs = packs.isEmpty() ? pack.get().id() : packs + "+" + pack.get().id();
        }
        if (total <= 0) {
            log.warn("Paddle {}: no credit pack in this transaction, ignored", txnId);
            return Outcome.IGNORED;
        }
        long amount = parseLong(tx.path("details").path("totals").path("total").asString("0"));
        String currency = tx.path("currency_code").asString("USD");
        try {
            payments.saveAndFlush(Payment.completed(txnId, userId, packs, total, amount, currency));
        } catch (DataIntegrityViolationException e) {
            return Outcome.DUPLICATE; // the same webhook, at the same moment, on another thread
        }
        int balance = credits.purchase(userId, total, "Pack " + packs + " (" + txnId + ")");
        log.info("Paddle {}: +{} credits for user {} (pack {}), balance {}", txnId, total, userId, packs, balance);
        return Outcome.CREDITED;
    }

    private Outcome adjusted(JsonNode adj) {
        String action = adj.path("action").asString("");
        if (!(action.equals("refund") || action.equals("chargeback")) || !"approved".equals(adj.path("status").asString(""))) {
            return Outcome.IGNORED;
        }
        String txnId = adj.path("transaction_id").asString("");
        Optional<Payment> found = payments.lockByPaddleTransactionId(txnId);
        if (found.isEmpty() || found.get().getStatus() == PaymentStatus.REFUNDED) {
            return Outcome.IGNORED;
        }
        Payment payment = found.get();
        int toTake = payment.getCredits();
        if (!"full".equals(adj.path("type").asString("partial")) && payment.getAmountMinor() > 0) {
            // partial refund: the same share of the credits
            long refunded = parseLong(adj.path("totals").path("total").asString("0"));
            toTake = BigDecimal.valueOf(payment.getCredits())
                    .multiply(BigDecimal.valueOf(refunded))
                    .divide(BigDecimal.valueOf(payment.getAmountMinor()), 0, RoundingMode.CEILING)
                    .intValue();
            toTake = Math.min(Math.max(toTake, 0), payment.getCredits());
        }
        int taken = credits.takeBack(payment.getUserId(), toTake, "Payment " + action + " (" + txnId + ")");
        payment.refunded(taken);
        log.warn("Paddle {} {}: took back {} of {} credits from user {}", txnId, action, taken, toTake, payment.getUserId());
        return Outcome.REFUNDED;
    }

    private Optional<PaddleProperties.Pack> packFor(String priceId) {
        return props.sellable().stream().filter(p -> p.priceId().equals(priceId)).findFirst();
    }

    private static UUID parseUuid(String s) {
        try {
            return s == null || s.isBlank() ? null : UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
