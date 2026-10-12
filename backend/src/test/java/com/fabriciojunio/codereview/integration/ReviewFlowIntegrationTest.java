package com.fabriciojunio.codereview.integration;

import com.fabriciojunio.codereview.dto.AuthResponse;
import com.fabriciojunio.codereview.dto.RegisterRequest;
import com.fabriciojunio.codereview.dto.ReviewRequest;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import com.fabriciojunio.codereview.messaging.ReviewProducer;
import com.fabriciojunio.codereview.model.Review.Language;
import com.fabriciojunio.codereview.service.ReviewService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.AmqpConnectException;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewService reviews;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("a queue consumer sees the committed ticket; rollback sends nothing")
    void deliveryWaitsForCommit() throws Exception {
        String email = "commit+" + UUID.randomUUID() + "@example.com";
        register(email);
        var transactions = new TransactionTemplate(transactionManager);
        var delivered = new ArrayList<UUID>();
        doAnswer(invocation -> {
            UUID ticket = UUID.fromString(invocation.getArgument(2));
            // A fresh connection models the consumer, rather than reading the
            // uncommitted entity through the producer's persistence context.
            try (var connection = jdbc.getDataSource().getConnection();
                 var statement = connection.prepareStatement("select count(*) from reviews where id = ?")) {
                statement.setObject(1, ticket);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
            }
            delivered.add(ticket);
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));

        UUID committed = transactions.execute(transaction -> {
            var response = reviews.submit(new ReviewRequest("class A {}", Language.java, null), email);
            assertThat(delivered).isEmpty();
            return response.ticketId();
        });
        assertThat(delivered).containsExactly(committed);
        UUID rolledBack = transactions.execute(transaction -> {
            var response = reviews.submit(new ReviewRequest("class B {}", Language.java, null), email);
            transaction.setRollbackOnly();
            return response.ticketId();
        });
        assertThat(delivered).containsExactly(committed);
        assertThat(jdbc.queryForObject("select count(*) from reviews where id = ?", Integer.class, rolledBack)).isZero();
    }

    @Test
    @DisplayName("broker failure becomes a failed ticket instead of an eternal pending review")
    void brokerFailureIsVisible() throws Exception {
        String email = "broker+" + UUID.randomUUID() + "@example.com";
        AuthResponse auth = register(email);
        doThrow(new AmqpConnectException(new java.net.ConnectException("test broker unavailable")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));
        ReviewResponse response = reviews.submit(new ReviewRequest("class A {}", Language.java, null), email);
        mockMvc.perform(get("/api/v1/reviews/" + response.ticketId())
                        .header("Authorization", "Bearer " + auth.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value("Queue delivery failed. Please submit the review again."));
    }

    private AuthResponse register(String email) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("Audit User", email, "password123"))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
    }

    @Test
    @DisplayName("concurrent requests share the final slot of the account quota")
    void concurrentQuotaIsAtomic() throws Exception {
        String email = "quota+" + UUID.randomUUID() + "@example.com";
        AuthResponse auth = register(email);
        jdbc.update("update users set review_count_this_hour = 19, review_window_start = now() where email = ?", email);
        String body = objectMapper.writeValueAsString(new ReviewRequest("class A {}", Language.java, "A.java"));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var pending = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 8; i++) {
                pending.add(executor.submit(() -> {
                    assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                    return mockMvc.perform(post("/api/v1/reviews")
                                    .header("Authorization", "Bearer " + auth.token())
                                    .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : pending) statuses.add(future.get(60, TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(202, 429);
            assertThat(statuses.stream().filter(s -> s == 202).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select review_count_this_hour from users where email = ?", Integer.class, email))
                    .isEqualTo(20);
            assertThat(jdbc.queryForObject("select count(*) from reviews r join users u on r.user_id = u.id where u.email = ?", Integer.class, email))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("file uploads enforce source limits without consuming quota or persisting reviews")
    void invalidUploadsAreRejected() throws Exception {
        String email = "upload+" + UUID.randomUUID() + "@example.com";
        AuthResponse auth = register(email);
        for (String code : new String[]{"", " \n\t", "a".repeat(50001)}) {
            var file = new MockMultipartFile("file", "A.java", "text/plain", code.getBytes(StandardCharsets.UTF_8));
            mockMvc.perform(multipart("/api/v1/reviews/upload").file(file).param("language", "java")
                            .header("Authorization", "Bearer " + auth.token()))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("select review_count_this_hour from users where email = ?", Integer.class, email)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from reviews r join users u on r.user_id = u.id where u.email = ?", Integer.class, email)).isZero();
    }

    @Test
    @DisplayName("a second account cannot read another account's review or stream")
    void ticketsAreIsolatedBetweenAccounts() throws Exception {
        AuthResponse owner = register("owner+" + UUID.randomUUID() + "@example.com");
        AuthResponse other = register("other+" + UUID.randomUUID() + "@example.com");
        var submitted = mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReviewRequest("class A {}", Language.java, null))))
                .andExpect(status().isAccepted()).andReturn();
        var ticket = objectMapper.readValue(submitted.getResponse().getContentAsString(), ReviewResponse.class).ticketId();
        mockMvc.perform(get("/api/v1/reviews/" + ticket).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/reviews/" + ticket + "/stream").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/reviews/history").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

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
