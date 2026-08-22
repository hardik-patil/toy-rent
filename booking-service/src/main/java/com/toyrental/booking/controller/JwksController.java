package com.toyrental.booking.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * booking-service is the only issuer of JWTs in this system (self-signed, see JwtKeyConfig),
 * so other services validate its tokens by fetching the public half of that same keypair here
 * rather than trusting a hardcoded/shared key — the keypair is regenerated fresh in memory on
 * every restart, so a static key would go stale the moment this service restarts. Public only:
 * toPublicJWK() strips the private key material before it ever leaves this service.
 */
@Tag(name = "JWKS", description = "Public key set for validating booking-service-issued JWTs")
@RestController
public class JwksController {

    private final RSAKey rsaKey;

    public JwksController(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    @Operation(summary = "JSON Web Key Set — the public key other services use to validate tokens this service issues")
    @GetMapping("/oauth2/jwks")
    public Map<String, Object> jwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }

}
