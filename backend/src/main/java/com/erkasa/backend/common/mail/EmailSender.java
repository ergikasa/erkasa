package com.erkasa.backend.common.mail;

/**
 * Sends one email. An interface so tests can replace it with a mock and read the links in the emails.
 * The real implementation is {@link SmtpEmailSender}.
 */
public interface EmailSender {

    void send(EmailMessage message);
}