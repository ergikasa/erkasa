package com.erkasa.backend.credits;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "erkasa.credits" in application.yml.
 *
 * @param signupBonus credits a new account gets (1 credit = 1 second of video)
 */
@ConfigurationProperties(prefix = "erkasa.credits")
public record CreditProperties(Integer signupBonus) {

    public CreditProperties {
        signupBonus = signupBonus == null || signupBonus < 0 ? 30 : signupBonus;
    }
}
