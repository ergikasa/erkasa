package com.erkasa.backend.credits;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.erkasa.backend.auth.UserRepository;
import com.erkasa.backend.common.error.ApiException;

/**
 *   GET  /api/credits                 your balance and the latest changes
 *   POST /api/admin/credits           ADMIN only: give credits to an account (by email)
 */
@RestController
public class CreditController {

    private final CreditService credits;
    private final UserRepository users;

    public CreditController(CreditService credits, UserRepository users) {
        this.credits = credits;
        this.users = users;
    }

    public record TransactionView(UUID id, int amount, int balanceAfter, CreditReason reason, UUID projectId,
                                  String note, Instant createdAt) {
        static TransactionView of(CreditTransaction t) {
            return new TransactionView(t.getId(), t.getAmount(), t.getBalanceAfter(), t.getReason(), t.getProjectId(),
                    t.getNote(), t.getCreatedAt());
        }
    }

    public record CreditsResponse(int balance, List<TransactionView> transactions) {
    }

    @GetMapping("/api/credits")
    public CreditsResponse mine(@RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return new CreditsResponse(credits.balance(userId),
                credits.history(userId, limit).stream().map(TransactionView::of).toList());
    }

    public record GrantRequest(@NotBlank @Email String email,
                               @NotNull(message = "Amount is required")
                               @Min(value = 1, message = "At least 1") @Max(value = 100_000, message = "At most 100000") Integer amount,
                               @Size(max = 200) String note) {
    }

    public record GrantResponse(String email, int balance) {
    }

    @PostMapping("/api/admin/credits")
    @PreAuthorize("hasRole('ADMIN')")
    public GrantResponse grant(@Valid @RequestBody GrantRequest body, @AuthenticationPrincipal Jwt jwt) {
        String email = body.email().trim().toLowerCase(java.util.Locale.ROOT);
        var user = users.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No account with this email"));
        String note = (body.note() == null || body.note().isBlank() ? "Given by admin" : body.note().trim())
                + " (by " + jwt.getClaimAsString("email") + ")";
        return new GrantResponse(email, credits.grant(user.getId(), body.amount(), note));
    }
}
