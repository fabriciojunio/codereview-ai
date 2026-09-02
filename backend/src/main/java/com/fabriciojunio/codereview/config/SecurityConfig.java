package com.fabriciojunio.codereview.config;

import com.fabriciojunio.codereview.security.JwtFilter;
import com.fabriciojunio.codereview.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Optional;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    /**
     * Presente só quando um provedor de identidade externo foi configurado.
     * Ver {@link IdentidadeExterna}.
     */
    private final Optional<Converter<Jwt, ? extends AbstractAuthenticationToken>> conversorDeTokenExterno;

    /**
     * Origens permitidas para CORS. Vazio por padrão (deny-by-default): nenhuma
     * origem cross-site é aceita a menos que explicitamente configurada via
     * SECURITY_CORS_ALLOWED_ORIGINS.
     */
    @Value("${security.cors.allowed-origins:}")
    private List<String> corsAllowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        ligarIdentidadeExterna(http);

        return http.build();
    }

    /**
     * Liga a validação de token de terceiro, quando existe um provedor.
     *
     * <p>A chamada só acontece quando há conversor. Chamar
     * {@code oauth2ResourceServer} com o bloco vazio não é inofensivo: o
     * Spring Security lança na subida dizendo que só aceita JWT ou token
     * opaco e não achou nenhum dos dois. Descobri isso quebrando nove testes
     * de contexto de uma vez.
     *
     * <p>Os dois modos convivem porque o filtro próprio ignora token que não
     * emitiu, e o servidor de recursos ignora requisição que já chegou
     * autenticada. Quem apresenta um token do provedor entra por aqui; quem
     * usa o login local entra pelo filtro.
     */
    private void ligarIdentidadeExterna(HttpSecurity http) throws Exception {
        if (conversorDeTokenExterno.isEmpty()) {
            return;
        }
        http.oauth2ResourceServer(oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(conversorDeTokenExterno.get())));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (corsAllowedOrigins != null && !corsAllowedOrigins.isEmpty()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(corsAllowedOrigins);
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            config.setAllowCredentials(true);
            config.setMaxAge(3600L);
            source.registerCorsConfiguration("/**", config);
        }
        // Sem origens configuradas: nenhuma regra registrada => CORS negado por padrão.
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Strength 12: custo de hashing deliberadamente alto contra força bruta.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
