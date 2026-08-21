package com.toyrental.booking.service;

import com.toyrental.booking.entity.Customer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class JwtTokenService {

    public static final long EXPIRY_SECONDS = 24 * 60 * 60;

    private final JwtEncoder jwtEncoder;

    public JwtTokenService(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    public String issueToken(Customer customer) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("booking-service")
                .issuedAt(now)
                .expiresAt(now.plus(EXPIRY_SECONDS, ChronoUnit.SECONDS))
                .subject(customer.getId())
                .claim("phone", customer.getPhone())
                .claim("name", customer.getName())
                .claim("roles", List.of("CUSTOMER"))
                .build();

        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

}
