package com.fabriciojunio.codereview.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "review_count_this_hour")
    @Builder.Default
    private int reviewCountThisHour = 0;

    @Column(name = "review_window_start")
    private Instant reviewWindowStart;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
