package com.erkasa.backend.common.mail;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Turns on @Async. Spring Boot provides the thread pool ("applicationTaskExecutor"). */
@Configuration
@EnableAsync
public class AsyncConfig {
}