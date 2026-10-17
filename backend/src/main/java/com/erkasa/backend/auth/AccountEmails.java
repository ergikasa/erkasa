package com.erkasa.backend.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import com.erkasa.backend.common.mail.EmailMessage;

/**
 * The text of the account emails. The links point to the FRONTEND, which reads the token
 * from the URL and calls the backend (POST /api/auth/verify-email or /reset-password).
 *
 * English only for now; Albanian versions come with i18n in the frontend phase.
 */
@Component
public class AccountEmails {

    private final String frontendUrl;

    public AccountEmails(@Value("${erkasa.app.frontend-url}") String frontendUrl) {
        this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
    }

    public EmailMessage verifyEmail(User user, String rawToken) {
        String link = link("/verify-email", rawToken);
        String greeting = greeting(user);
        String text = """
                %s

                Welcome to erkasa! Please confirm your email address by opening this link:

                %s

                The link is valid for 24 hours. If you did not create an account, you can ignore this email.
                """.formatted(greeting, link);
        String html = layout(greeting,
                "Welcome to erkasa! Please confirm your email address.",
                link, "Confirm email",
                "The link is valid for 24 hours. If you did not create an account, you can ignore this email.");
        return new EmailMessage(user.getEmail(), "Confirm your email for erkasa", text, html);
    }

    public EmailMessage resetPassword(User user, String rawToken) {
        String link = link("/reset-password", rawToken);
        String greeting = greeting(user);
        String text = """
                %s

                We received a request to reset your erkasa password. Open this link to choose a new one:

                %s

                The link is valid for 1 hour. If you did not ask for this, ignore this email: your password stays the same.
                """.formatted(greeting, link);
        String html = layout(greeting,
                "We received a request to reset your erkasa password.",
                link, "Choose a new password",
                "The link is valid for 1 hour. If you did not ask for this, ignore this email: your password stays the same.");
        return new EmailMessage(user.getEmail(), "Reset your erkasa password", text, html);
    }

    private String link(String path, String rawToken) {
        return frontendUrl + path + "?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    private static String greeting(User user) {
        return user.getDisplayName() != null ? "Hi " + user.getDisplayName() + "," : "Hi,";
    }

    /** A small, inline-styled HTML email (email apps ignore <style> blocks and external CSS). */
    private static String layout(String greeting, String intro, String link, String button, String footer) {
        String safeLink = HtmlUtils.htmlEscape(link);
        return """
                <!doctype html>
                <html><body style="margin:0;padding:24px;background:#f5f5f5;font-family:Arial,sans-serif;color:#1a1a1a">
                  <div style="max-width:520px;margin:0 auto;background:#ffffff;border-radius:8px;padding:32px">
                    <p style="font-size:16px;margin:0 0 16px">%s</p>
                    <p style="font-size:16px;margin:0 0 24px">%s</p>
                    <p style="margin:0 0 24px">
                      <a href="%s" style="display:inline-block;background:#111111;color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:6px;font-size:15px">%s</a>
                    </p>
                    <p style="font-size:13px;color:#666666;margin:0 0 8px">Or copy this link into your browser:</p>
                    <p style="font-size:13px;color:#666666;word-break:break-all;margin:0 0 24px">%s</p>
                    <p style="font-size:13px;color:#666666;margin:0">%s</p>
                  </div>
                </body></html>
                """.formatted(
                HtmlUtils.htmlEscape(greeting),
                HtmlUtils.htmlEscape(intro),
                safeLink,
                HtmlUtils.htmlEscape(button),
                safeLink,
                HtmlUtils.htmlEscape(footer));
    }
}