package com.erkasa.backend.credits;

/** Why a balance changed. Same values as the CHECK in V9__credits.sql. */
public enum CreditReason {
    /** welcome credits for a new account */
    SIGNUP_BONUS,
    /** a video was approved (its files start costing us money) */
    VIDEO_CHARGE,
    /** the video failed or was cancelled: its charge goes back */
    VIDEO_REFUND,
    /** given by an admin (support, testing, before payments exist) */
    ADMIN_GRANT,
    /** bought with a credit pack (Paddle, phase 8) */
    PURCHASE,
    /** a purchase was refunded or charged back: its credits go away (never below 0) */
    PURCHASE_REFUND
}
