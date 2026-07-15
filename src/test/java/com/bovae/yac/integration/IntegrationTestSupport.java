package com.bovae.yac.integration;

import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.UserService;

/**
 * Shared arrangement helpers for the integration tests in this package.
 */
final class IntegrationTestSupport {

    /** Default password used for all test users registered via {@link #registerUser}. */
    static final String DEFAULT_PASSWORD = "testpass123";

    private IntegrationTestSupport() {}

    /**
     * Registers a user through the service and returns the persisted entity.
     *
     * @param userService    service used to register the account
     * @param userRepository repository used to reload the persisted entity
     * @param email          the user's email address
     * @param username       the user's username
     * @return the persisted {@link User}
     */
    static User registerUser(UserService userService, UserRepository userRepository, String email, String username) {
        UserDto dto = userService.register(email, username, DEFAULT_PASSWORD);
        return userRepository.findById(dto.id()).orElseThrow();
    }
}
