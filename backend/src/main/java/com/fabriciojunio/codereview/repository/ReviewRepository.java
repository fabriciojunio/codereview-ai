package com.fabriciojunio.codereview.repository;

import com.fabriciojunio.codereview.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByUserIdOrderBySubmittedAtDesc(UUID userId, Pageable pageable);

    @Query("SELECT r FROM Review r LEFT JOIN FETCH r.result WHERE r.id = :id")
    Optional<Review> findByIdWithResult(UUID id);
}
