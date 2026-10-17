package com.erkasa.backend.common.mail;

/**
 * One email to send.
 *
 * @param to      recipient address
 * @param subject subject line
 * @param text    plain-text version (for email apps that do not show HTML)
 * @param html    HTML version
 */
public record EmailMessage(String to, String subject, String text, String html) {
}