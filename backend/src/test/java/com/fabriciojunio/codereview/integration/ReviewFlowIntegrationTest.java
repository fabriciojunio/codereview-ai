package com.fabriciojunio.codereview.integration;

import com.fabriciojunio.codereview.dto.AuthRequest;
import com.fabriciojunio.codereview.dto.AuthResponse;
import com.fabriciojunio.codereview.dto.RegisterRequest;
import com.fabriciojunio.codereview.dto.ReviewRequest;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import com.fabriciojunio.codereview.model.Review.Language;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ReviewFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("codereview_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        // Use embedded Redis or disable cache for tests
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6370"); // intentionally wrong — handled by CacheService fallback
        // Disable Ollama for integration tests
        registry.add("ollama.base-url", () -> "http://localhost:99999");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void fullFlow_registerLoginSubmit_returnsTicketId() throws Exception {
        // 1. Register
        RegisterRequest register = new RegisterRequest("Fabrício Júnio", "fab@example.com", "password123");
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse authResponse = objectMapper.readValue(
                registerResult.getResponse().getContentAsString(), AuthResponse.class);
        assertThat(authResponse.token()).isNotBlank();

        // 2. Submit review
        ReviewRequest reviewRequest = new ReviewRequest(
                "public class Hello { public static void main(String[] args) { System.out.println(\"Hi\"); } }",
                Language.java, null);

        MvcResult submitResult = mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + authResponse.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewRequest)))
                .andExpect(status().isAccepted())
                .andReturn();

        ReviewResponse reviewResponse = objectMapper.readValue(
                submitResult.getResponse().getContentAsString(), ReviewResponse.class);
        assertThat(reviewResponse.ticketId()).isNotNull();

        // 3. Check ticket status
        mockMvc.perform(get("/api/v1/reviews/" + reviewResponse.ticketId())
                        .header("Authorization", "Bearer " + authResponse.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(reviewResponse.ticketId().toString()));
    }

    @Test
    void register_duplicateEmail_returns400() throws Exception {
        RegisterRequest register = new RegisterRequest("User", "dup@example.com", "password123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isBadRequest());
    }
}
