package com.erkasa.backend.outbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled, used by {@link OutboxRelay}. */
@Configuration
@EnableScheduling
public class OutboxConfig {
}
