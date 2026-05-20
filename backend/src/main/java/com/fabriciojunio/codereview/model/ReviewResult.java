package com.fabriciojunio.codereview.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false, unique = true)
    private Review review;

    @Column(nullable = false)
    private int score;

    @Column(columnDefinition = "TEXT")
    private String summary;

    // Stored as JSON text, deserialized in the service layer
    @Column(name = "bugs_json", columnDefinition = "TEXT")
    private String bugsJson;

    @Column(name = "code_smells_json", columnDefinition = "TEXT")
    private String codeSmellsJson;

    @Column(name = "solid_violations_json", columnDefinition = "TEXT")
    private String solidViolationsJson;

    @Column(name = "refactoring_suggestions_json", columnDefinition = "TEXT")
    private String refactoringSuggestionsJson;

    @Column(name = "positive_aspects_json", columnDefinition = "TEXT")
    private String positiveAspectsJson;

    @Column(name = "raw_llm_response", columnDefinition = "TEXT")
    private String rawLlmResponse;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @PrePersist
    protected void onCreate() {
        analyzedAt = Instant.now();
    }
}
