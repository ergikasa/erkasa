package com.erkasa.backend.common.mail;

/**
 * "Please send this email." Published by services; delivered by {@link EmailDispatcher}
 * only after the database transaction has committed.
 */
public record EmailRequestedEvent(EmailMessage message) {
}