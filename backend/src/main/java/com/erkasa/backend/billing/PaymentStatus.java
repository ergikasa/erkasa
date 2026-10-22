package com.erkasa.backend.billing;

public enum PaymentStatus {
    /** paid, credits given */
    COMPLETED,
    /** refunded or charged back, credits taken back (as far as possible) */
    REFUNDED
}
