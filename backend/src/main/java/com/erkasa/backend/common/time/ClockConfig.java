package com.erkasa.backend.common.time;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock for time rules that tests need to control (render leases and deadlines, V2 A3/A4).
 * Tests replace it with a fixed or movable clock instead of sleeping for minutes.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
