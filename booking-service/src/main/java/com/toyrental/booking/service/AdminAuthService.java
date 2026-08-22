package com.toyrental.booking.service;

import com.toyrental.booking.dto.AdminLoginRequest;
import com.toyrental.booking.dto.AdminLoginResponse;
import com.toyrental.booking.exception.InvalidCredentialsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Admin login checked against a single configured username/password rather than a customers-
 * table row — there is no "staff" concept in the schema, and admin is a platform-operator
 * identity, not a customer attribute. Swap for a real identity provider (Keycloak, as
 * CLAUDE.md originally intended) later without any customer-data cleanup, since nothing here
 * touches that table.
 */
@Slf4j
@Service
public class AdminAuthService {

    private final String adminUsername;
    private final String adminPassword;
    private final JwtTokenService jwtTokenService;

    public AdminAuthService(@Value("${admin.username:admin}") String adminUsername,
                             @Value("${admin.password:admin123}") String adminPassword,
                             JwtTokenService jwtTokenService) {
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.jwtTokenService = jwtTokenService;
    }

    public AdminLoginResponse login(AdminLoginRequest request) {
        if (!adminUsername.equals(request.username()) || !adminPassword.equals(request.password())) {
            throw new InvalidCredentialsException("Invalid admin username or password");
        }

        String token = jwtTokenService.issueAdminToken(adminUsername);
        log.info("Admin username={} logged in", adminUsername);
        return new AdminLoginResponse(token, "Bearer", JwtTokenService.EXPIRY_SECONDS);
    }

}
