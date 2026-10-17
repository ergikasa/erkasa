package com.erkasa.backend.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Database access for users. Spring writes the SQL from the method names. */
public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    Optional<User> findByGoogleSub(String googleSub);
}