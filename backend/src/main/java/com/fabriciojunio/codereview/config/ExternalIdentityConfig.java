package com.fabriciojunio.codereview.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accepts tokens issued by an external identity provider.
 *
 * <p>The built-in login exists so the application can run on its own, and it
 * stays the default. Inside a company, though, authentication almost never
 * belongs to the application: it comes from the Keycloak, Entra ID or Okta the
 * identity team already runs. An internal tool that demands its own signup is a
 * tool nobody adopts, and one more place holding passwords.
 *
 * <p>Stays off until someone points
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} at an issuer.
 * Once on, Spring downloads the provider's public keys and validates signature,
 * issuer and expiry by itself. The application never sees a password and never
 * stores a signing key.
 *
 * <p>Same shape as the other swap points in this project: one more thing turned
 * on by configuration, with no parallel path through the code.
 */
@Configuration
@ConditionalOnProperty(name = "spring.security.oauth2.resourceserver.jwt.issuer-uri")
public class ExternalIdentityConfig {

    /**
     * Where Keycloak puts domain roles.
     *
     * <p>There is no standard for this. OpenID Connect defines identity claims,
     * not authorization claims, so every provider invented its own place.
     * Reading both common shapes covers most installations without asking for
     * configuration.
     */
    private static final String KEYCLOAK_CLAIM = "realm_access";
    private static final String ROLES_CLAIM = "roles";

    /** Prefix Spring Security expects on a role, by convention. */
    private static final String ROLE_PREFIX = "ROLE_";

    @Bean
    public Converter<Jwt, ? extends AbstractAuthenticationToken> externalTokenConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(ExternalIdentityConfig::authoritiesOf);

        // The user id comes from "sub", not from the e-mail or the login name:
        // both of those change, "sub" does not. An audit trail keyed on e-mail
        // points at the wrong person after a marriage or a divorce.
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    /**
     * Merges OAuth2 scopes with the provider's roles.
     *
     * <p>They are two different things that get confused. A scope is what the
     * client application may ask for; a role is what the person may do. A token
     * can carry a read scope and an admin role at the same time, and both
     * matter.
     */
    private static Collection<GrantedAuthority> authoritiesOf(Jwt token) {
        Set<GrantedAuthority> authorities = new HashSet<>(
                new JwtGrantedAuthoritiesConverter().convert(token));

        rolesOf(token).forEach(role ->
                authorities.add(new SimpleGrantedAuthority(withPrefix(role))));

        return authorities;
    }

    @SuppressWarnings("unchecked")
    private static List<String> rolesOf(Jwt token) {
        // Keycloak shape: {"realm_access": {"roles": [...]}}
        Object realm = token.getClaim(KEYCLOAK_CLAIM);
        if (realm instanceof Map<?, ?> map && map.get(ROLES_CLAIM) instanceof List<?> list) {
            return (List<String>) list;
        }

        // Flat shape, used by several others: {"roles": [...]}
        Object flat = token.getClaim(ROLES_CLAIM);
        if (flat instanceof List<?> list) {
            return (List<String>) list;
        }

        return List.of();
    }

    /**
     * Does not double the prefix when the provider already sends it.
     *
     * <p>Without this check, a provider emitting {@code ROLE_ADMIN} turns into
     * {@code ROLE_ROLE_ADMIN} and authorization fails silently: the token is
     * valid, the user is authenticated, and access is denied with no
     * explanation.
     */
    private static String withPrefix(String role) {
        return role.startsWith(ROLE_PREFIX) ? role : ROLE_PREFIX + role;
    }
}
