package com.erkasa.backend.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends requested emails in the background, after the transaction commits.
 *
 * Why after commit: if registration failed and rolled back, we must not have sent a
 * "verify your email" link for an account that does not exist.
 * Why in the background (@Async): an SMTP server can take seconds; the user should not wait.
 * A failed send is logged, never shown to the user: they can ask for a new link.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final EmailSender sender;

    public EmailDispatcher(EmailSender sender) {
        this.sender = sender;
    }

    /** fallbackExecution = true: also works when the event is published outside a transaction. */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onEmailRequested(EmailRequestedEvent event) {
        String subject = event.message().subject();
        try {
            sender.send(event.message());
            log.info("Email sent: {}", subject);
        } catch (Exception e) {
            log.error("Could not send email: {}", subject, e);
        }
    }
}