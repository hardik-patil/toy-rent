package com.toyrental.booking.config;

import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * booking-service issues its own customer JWTs rather than delegating to Keycloak (no
 * toyrental realm/client is provisioned yet, and the customers table already carries its own
 * password_hash column — see PROGRESS.md Session 3 for the decision). The keypair is generated
 * fresh on every startup: tokens don't survive a restart, which is acceptable for this stage —
 * revisit with a persisted or externally-supplied key if that ever matters.
 */
@Configuration
public class JwtKeyConfig {

    @Bean
    public RSAKey rsaKey() {
        KeyPair keyPair = generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to generate RSA keypair for JWT signing", e);
        }
    }

}
