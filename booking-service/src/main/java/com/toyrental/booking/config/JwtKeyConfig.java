package com.toyrental.booking.config;

import com.nimbusds.jose.jwk.RSAKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * booking-service issues its own customer/admin JWTs rather than delegating to Keycloak (no
 * toyrental realm/client is provisioned yet, and the customers table already carries its own
 * password_hash column — see PROGRESS.md Session 3 for the decision).
 *
 * <p>Real bug caught by live testing (2026-10-01): this used to generate a fresh random RSA
 * keypair on every JVM startup. That's fine for a single instance, but booking-service runs 2
 * replicas — each pod minted its own key with its own random {@code kid}, so a token signed by
 * pod A was rejected ({@code 401}) by anything validating against pod B's key: toy-service's
 * {@code jwk-set-uri} fetch goes through the K8s Service (load-balanced across both pods and
 * cached), and booking-service's sibling pod would reject it too under real (non-pinned)
 * traffic. Not a flake — deterministic per which pod issued vs which pod's key the validator
 * has. Reproduced: a token from one pod was rejected by toy-service 5/5 times. See CLAUDE.md's
 * Known Bugs table (2026-10-01 entry).
 *
 * <p>Fix: load a shared keypair + key ID from JWT_PRIVATE_KEY/JWT_PUBLIC_KEY/JWT_SIGNING_KEY_ID
 * (PEM-encoded PKCS8 private / X.509 public, see booking-service-secret in
 * k8s/services/booking-service/booking-service.yaml — same dev-placeholder convention as the
 * other secrets there) so every replica signs and validates with the identical key. Falls back
 * to generating an ephemeral keypair when those env vars aren't set, so a plain local run
 * (mvn spring-boot:run, no K8s secret mounted) still works exactly as before — just not safe
 * for more than one concurrently-running instance.
 */
@Slf4j
@Configuration
public class JwtKeyConfig {

    @Bean
    public RSAKey rsaKey(@Value("${JWT_PRIVATE_KEY:}") String privateKeyPem,
                          @Value("${JWT_PUBLIC_KEY:}") String publicKeyPem,
                          @Value("${JWT_SIGNING_KEY_ID:}") String keyId) {
        if (!privateKeyPem.isBlank() && !publicKeyPem.isBlank()) {
            log.info("Loading shared JWT signing key (kid={}) from JWT_PRIVATE_KEY/JWT_PUBLIC_KEY", keyId);
            return loadSharedKey(privateKeyPem, publicKeyPem, keyId);
        }

        log.warn("JWT_PRIVATE_KEY/JWT_PUBLIC_KEY not set - generating an ephemeral per-instance JWT " +
                "signing key. Fine for a single local instance; with more than one replica running " +
                "concurrently, tokens signed by this instance will be rejected by any validator that " +
                "doesn't have this instance's key (see this class's Javadoc, CLAUDE.md's Known Bugs " +
                "2026-10-01 entry). Set those env vars (see booking-service-secret) to fix.");
        KeyPair keyPair = generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    private RSAKey loadSharedKey(String privateKeyPem, String publicKeyPem, String keyId) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");

            byte[] privateKeyBytes = Base64.getDecoder().decode(stripPemHeaders(privateKeyPem));
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));

            byte[] publicKeyBytes = Base64.getDecoder().decode(stripPemHeaders(publicKeyPem));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            String resolvedKeyId = keyId.isBlank() ? "default" : keyId;
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(resolvedKeyId)
                    .build();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to load shared JWT signing key from " +
                    "JWT_PRIVATE_KEY/JWT_PUBLIC_KEY - check they're valid PKCS8/X.509 PEM", e);
        }
    }

    private String stripPemHeaders(String pem) {
        return pem
                .replaceAll("-----BEGIN (PRIVATE|PUBLIC) KEY-----", "")
                .replaceAll("-----END (PRIVATE|PUBLIC) KEY-----", "")
                .replaceAll("\\s", "");
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
