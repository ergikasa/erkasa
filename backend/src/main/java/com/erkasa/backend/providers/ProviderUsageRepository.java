package com.erkasa.backend.providers;


import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderUsageRepository extends JpaRepository<ProviderUsageRecord, UUID> {
}