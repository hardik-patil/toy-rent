package com.toyrental.toy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/internal/v1/toys/**").permitAll()
                        // Bug caught by live testing: requestMatchers(String, String...) has no overload
                        // that takes an HTTP method as a String — "GET" was being matched as a second URL
                        // pattern (which never matches any real path), so this rule permitAll()'d every
                        // method on /api/v1/toys/**, including POST/PUT/DELETE, regardless of the ADMIN
                        // rules below. Must use the HttpMethod overload to actually restrict by verb.
                        .requestMatchers(HttpMethod.GET, "/api/v1/toys/**").permitAll()
                        // Writes on the /api/v1/toys/** resource (create/update/delete/image-upload) are
                        // admin-only per CLAUDE.md's endpoint reference, even though they don't live under
                        // /api/v1/admin/**. Must be matched before the GET permitAll rule above would not
                        // catch these (different HTTP methods) and before the generic authenticated() below
                        // would under-restrict them to "any logged-in user".
                        .requestMatchers(HttpMethod.POST, "/api/v1/toys", "/api/v1/toys/*/images/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/toys/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/toys/*").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    /**
     * booking-service (this system's only token issuer, see application.yml's jwk-set-uri
     * comment) puts roles in a flat "roles" claim (e.g. ["ADMIN"]) rather than Keycloak's
     * nested "realm_access.roles" shape — mirrors booking-service's own SecurityConfig
     * exactly, since both services must agree on how to read the same tokens.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::extractAuthorities);
        return converter;
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        Collection<GrantedAuthority> scopeAuthorities = scopeConverter.convert(jwt);

        List<String> roles = jwt.getClaimAsStringList("roles");
        Stream<GrantedAuthority> roleAuthorities = roles == null ? Stream.empty()
                : roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));

        return Stream.concat(scopeAuthorities == null ? Stream.empty() : scopeAuthorities.stream(), roleAuthorities)
                .collect(Collectors.toList());
    }

}
