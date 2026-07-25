package com.fabriciojunio.codereview.controller;

import com.fabriciojunio.codereview.dto.ReviewRequest;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import com.fabriciojunio.codereview.config.SecurityConfig;
import com.fabriciojunio.codereview.model.Review.Language;
import com.fabriciojunio.codereview.model.Review.ReviewStatus;
import com.fabriciojunio.codereview.security.JwtFilter;
import com.fabriciojunio.codereview.security.JwtProvider;
import com.fabriciojunio.codereview.security.RestAuthenticationEntryPoint;
import com.fabriciojunio.codereview.service.GitHubFetcher;
import com.fabriciojunio.codereview.service.OllamaService;
import com.fabriciojunio.codereview.service.PromptBuilder;
import com.fabriciojunio.codereview.service.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReviewController.class)
@Import({SecurityConfig.class, JwtFilter.class, RestAuthenticationEntryPoint.class})
class ReviewControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // Controller dependencies
    @MockBean private ReviewService reviewService;
    @MockBean private GitHubFetcher gitHubFetcher;
    @MockBean private OllamaService ollamaService;
    @MockBean private PromptBuilder promptBuilder;

    // Security infrastructure beans needed by JwtFilter (loaded by @WebMvcTest)
    @MockBean private JwtProvider jwtProvider;
    @MockBean private UserDetailsService userDetailsService;

    @Test
    @WithMockUser
    void submit_validRequest_returns202() throws Exception {
        UUID ticketId = UUID.randomUUID();
        ReviewResponse response = ReviewResponse.pending(ticketId, Language.java, Instant.now());
        when(reviewService.submit(any(ReviewRequest.class), anyString())).thenReturn(response);

        ReviewRequest request = new ReviewRequest("public class A {}", Language.java, null);

        mockMvc.perform(post("/api/v1/reviews")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @WithMockUser
    void submit_blankSourceCode_returns400() throws Exception {
        ReviewRequest request = new ReviewRequest("", Language.java, null);

        mockMvc.perform(post("/api/v1/reviews")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_unauthenticated_returns401() throws Exception {
        ReviewRequest request = new ReviewRequest("public class A {}", Language.java, null);

        mockMvc.perform(post("/api/v1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void getResult_existingTicket_returns200() throws Exception {
        UUID ticketId = UUID.randomUUID();
        ReviewResponse response = new ReviewResponse(
                ticketId, ReviewStatus.COMPLETED, Language.java,
                85, "Good code", null, null, null, null, null,
                Instant.now(), Instant.now(), null);

        when(reviewService.getResult(any(UUID.class), anyString())).thenReturn(response);

        mockMvc.perform(get("/api/v1/reviews/" + ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(85))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
