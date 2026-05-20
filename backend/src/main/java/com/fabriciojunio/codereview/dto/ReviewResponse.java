package com.fabriciojunio.codereview.dto;

import com.fabriciojunio.codereview.model.Bug;
import com.fabriciojunio.codereview.model.CodeSmell;
import com.fabriciojunio.codereview.model.Review.Language;
import com.fabriciojunio.codereview.model.Review.ReviewStatus;
import com.fabriciojunio.codereview.model.SolidViolation;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewResponse(
        UUID ticketId,
        ReviewStatus status,
        Language language,
        Integer score,
        String summary,
        List<Bug> bugs,
        List<CodeSmell> codeSmells,
        List<SolidViolation> solidViolations,
        List<String> refactoringSuggestions,
        List<String> positiveAspects,
        Instant submittedAt,
        Instant analyzedAt,
        String errorMessage
) {
    public static ReviewResponse pending(UUID ticketId, Language language, Instant submittedAt) {
        return new ReviewResponse(ticketId, ReviewStatus.PENDING, language,
                null, null, null, null, null, null, null, submittedAt, null, null);
    }
}
