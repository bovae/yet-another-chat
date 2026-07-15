package com.bovae.yac.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Security-related configuration. Startup fails when {@code app.security.remember-me-key}
 * (env {@code REMEMBER_ME_KEY}) is missing or blank — there is no hardcoded default.
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(@NotBlank String rememberMeKey) {}
