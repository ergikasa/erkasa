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
    ADMIN_GRANT
}
