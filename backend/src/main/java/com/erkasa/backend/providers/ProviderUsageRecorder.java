package com.erkasa.backend.providers;


import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves provider_usage rows.
 *
 * REQUIRES_NEW = its own small transaction. If the video job that made the call later fails and
 * rolls back, the cost row must stay: the provider charged us either way.
 * (ProviderGateway catches any error from here, so saving can never break the actual work.)
 */
@Service
public class ProviderUsageRecorder {

    private final ProviderUsageRepository repository;

    public ProviderUsageRecorder(ProviderUsageRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ProviderUsageRecord record) {
        repository.save(record);
    }
}