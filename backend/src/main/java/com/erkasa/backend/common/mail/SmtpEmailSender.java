package com.erkasa.backend.common.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Sends email over SMTP, configured by spring.mail.* in application.yml.
 * Locally the SMTP server is Mailpit (catches everything, UI at http://localhost:8025).
 * In production it is a real provider (Brevo, Resend, SES...), set by environment variables.
 */
@Component
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, @Value("${erkasa.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            // true = multipart, so the email carries both the plain-text and the HTML version.
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
            mailSender.send(mime);
        } catch (MessagingException e) {
            throw new IllegalStateException("Could not build email: " + message.subject(), e);
        }
    }
}