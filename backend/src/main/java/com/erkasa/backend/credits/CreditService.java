package com.erkasa.backend.credits;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erkasa.backend.auth.User;
import com.erkasa.backend.auth.UserRepository;
import com.erkasa.backend.common.error.ApiException;

/**
 * Credits: 1 credit = 1 second of video (phase 7).
 *
 * - a new account gets {@code signupBonus} credits;
 * - approving a script charges the video's length (that is when it starts costing us money);
 * - if the video fails or is cancelled, the charge goes back; "try again" charges it again.
 *
 * Every change locks the user's row, so two tabs approving at the same moment cannot spend the
 * same credits twice. The methods join the caller's transaction: callers lock the PROJECT first,
 * then this locks the USER (always in this order, so there are no deadlocks).
 */
@Service
@EnableConfigurationProperties(CreditProperties.class)
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    private final UserRepository users;
    private final CreditTransactionRepository transactions;
    private final CreditProperties props;

    public CreditService(UserRepository users, CreditTransactionRepository transactions, CreditProperties props) {
        this.users = users;
        this.transactions = transactions;
        this.props = props;
    }

    /** Welcome credits for an account that was just saved. */
    @Transactional
    public void grantSignupBonus(User user) {
        if (props.signupBonus() > 0) {
            change(user, null, props.signupBonus(), CreditReason.SIGNUP_BONUS, "Welcome credits");
        }
    }

    /** Support / testing before payments exist. */
    @Transactional
    public int grant(UUID userId, int amount, String note) {
        if (amount <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "The amount must be positive");
        }
        User user = lock(userId);
        change(user, null, amount, CreditReason.ADMIN_GRANT, note);
        return user.getCredits();
    }

    /**
     * Takes the video's price, unless it is already paid (a retry after a refund pays again).
     * 402 INSUFFICIENT_CREDITS when the balance is too low; nothing changes then.
     */
    @Transactional
    public void chargeForVideo(UUID userId, UUID projectId, int credits) {
        if (credits <= 0 || netCharged(projectId) > 0) {
            return;
        }
        User user = lock(userId);
        if (user.getCredits() < credits) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_CREDITS",
                    "This video needs " + credits + " credits; you have " + user.getCredits());
        }
        change(user, projectId, -credits, CreditReason.VIDEO_CHARGE, "Video " + credits + " s");
    }

    /** Gives back what a failed or cancelled video cost. Safe to call more than once. */
    @Transactional
    public void refundVideo(UUID userId, UUID projectId, String why) {
        int charged = netCharged(projectId);
        if (charged <= 0) {
            return;
        }
        change(lock(userId), projectId, charged, CreditReason.VIDEO_REFUND, why);
    }

    @Transactional(readOnly = true)
    public int balance(UUID userId) {
        return users.findById(userId).map(User::getCredits).orElse(0);
    }

    @Transactional(readOnly = true)
    public List<CreditTransaction> history(UUID userId, int limit) {
        return transactions.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }

    /** What the video costs the user right now (0 = nothing taken). */
    private int netCharged(UUID projectId) {
        return (int) -transactions.sumForProject(projectId);
    }

    private User lock(UUID userId) {
        return users.lockById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No such user"));
    }

    private void change(User user, UUID projectId, int amount, CreditReason reason, String note) {
        user.addCredits(amount);
        transactions.save(CreditTransaction.of(user.getId(), projectId, amount, user.getCredits(), reason, note));
        log.info("Credits {}{} for user {} ({}{}): balance {}", amount > 0 ? "+" : "", amount, user.getId(), reason,
                projectId == null ? "" : ", project " + projectId, user.getCredits());
    }
}
