package com.fabriciojunio.codereview.config;

import org.springframework.context.annotation.Configuration;

/**
 * WebFlux is on the classpath for reactive types (Mono/Flux) used in SSE streaming.
 * Spring MVC + WebFlux coexistence in a servlet container is supported out of the box
 * — no @EnableWebFlux annotation needed (that would switch the whole app to reactive mode).
 */
@Configuration
public class WebFluxConfig {
}
