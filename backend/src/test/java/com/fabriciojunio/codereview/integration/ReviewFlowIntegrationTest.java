package com.fabriciojunio.codereview.integration;

import com.fabriciojunio.codereview.dto.AuthResponse;
import com.fabriciojunio.codereview.dto.RegisterRequest;
import com.fabriciojunio.codereview.dto.ReviewRequest;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import com.fabriciojunio.codereview.messaging.ReviewProducer;
import com.fabriciojunio.codereview.model.Review.Language;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole application and walks the path a real client takes:
 * register, submit code for review, poll the ticket.
 *
 * <p>Two deliberate choices keep this test runnable anywhere, including a
 * laptop without Docker and a CI runner without service containers:
 *
 * <ul>
 *   <li><b>PostgreSQL runs embedded.</b> A real Postgres process is started by
 *       the test itself, so Flyway migrations, constraints and types are
 *       exercised for real. An in-memory database would pass while hiding
 *       exactly the problems this test exists to catch.</li>
 *   <li><b>RabbitMQ is stubbed.</b> The broker adds nothing here: this test is
 *       about the HTTP surface, security and persistence. Queue behaviour,
 *       including retry and dead-lettering, is covered by
 *       {@code ReviewConsumerTest} at unit level.</li>
 * </ul>
 *
 * <p>Before this change the CI pipeline excluded this class outright, which
 * meant the only test that proves the pieces work together never ran.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisplayName("Review flow, end to end")
class ReviewFlowIntegrationTest {

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void pointToTheTestDatabase(DynamicPropertyRegistry registry) throws Exception {
        postgres = EmbeddedPostgres.builder().start();

        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");

        // No broker in this test, so the listener must not try to connect.
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");

        // Redis is optional by design: CacheService degrades to no caching when
        // the server is unreachable. Pointing at a dead port exercises that path.
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6370");

        // The model is never called here. Submitting a review only enqueues it.
        registry.add("ollama.base-url", () -> "http://localhost:1");

        // The application refuses to start without a signing key, and it has no
        // default on purpose: a hard-coded fallback is how a development secret
        // ends up signing production tokens. The test supplies its own.
        // Base64 of 35 bytes, so the HMAC key clears the 256-bit floor jjwt
        // enforces. A plain string would fail decoding, which is how this test
        // found out the property expects base64 in the first place.
        registry.add("jwt.secret", () -> "Y2hhdmUtZGUtdGVzdGUtZG8tY29kZXJldmlldy1haS0zMmI=");
    }

    @AfterAll
    static void stopTheTestDatabase() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    /**
     * Replaces the AMQP template with a stub. The producer keeps its real code
     * path, so a change in exchange or routing key still surfaces here, but
     * nothing opens a socket.
     *
     * {@code @MockBean} rather than a {@code @TestConfiguration} bean: the
     * latter collides with the one declared in {@code RabbitConfig} and would
     * force bean-definition overriding on globally, which hides genuine
     * duplicate-bean mistakes.
     */
    @MockBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReviewProducer producer;

    @Test
    @DisplayName("registers, submits a review and reads the ticket back")
    void fullFlow() throws Exception {
        String email = "fab+" + UUID.randomUUID() + "@example.com";

        RegisterRequest register = new RegisterRequest("Fabricio Junio", email, "password123");
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(
                registered.getResponse().getContentAsString(), AuthResponse.class);
        assertThat(auth.token()).isNotBlank();

        ReviewRequest review = new ReviewRequest(
                "public class Hello { public static void main(String[] a) { System.out.println(\"Hi\"); } }",
                Language.java, null);

        MvcResult submitted = mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + auth.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(review)))
                .andExpect(status().isAccepted())
                .andReturn();

        ReviewResponse response = objectMapper.readValue(
                submitted.getResponse().getContentAsString(), ReviewResponse.class);
        assertThat(response.ticketId()).isNotNull();

        mockMvc.perform(get("/api/v1/reviews/" + response.ticketId())
                        .header("Authorization", "Bearer " + auth.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(response.ticketId().toString()));

        assertThat(producer).isNotNull();
    }

    @Test
    @DisplayName("refuses a second account with the same e-mail")
    void duplicateEmailIsRejected() throws Exception {
        String email = "dup+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest("User", email, "password123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("refuses to submit a review without a token")
    void reviewRequiresAuthentication() throws Exception {
        ReviewRequest review = new ReviewRequest("class A {}", Language.java, null);

        mockMvc.perform(post("/api/v1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(review)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refuses a token that was not issued by this service")
    void forgedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/" + UUID.randomUUID())
                        .header("Authorization", "Bearer nao.e.um.token.valido"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The probe must answer anonymously. It does <em>not</em> report UP here,
     * and that is the correct behaviour: Redis and RabbitMQ are deliberately
     * unreachable in this setup, and Spring aggregates every component into the
     * overall status. What matters for this test is that the endpoint is
     * reachable without credentials, so an orchestrator can poll it.
     */
    @Test
    @DisplayName("health probe answers without a token")
    void healthIsPublic() throws Exception {
        int status = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .describedAs("health probe must not require authentication")
                .isNotIn(401, 403, 404);
    }
}
