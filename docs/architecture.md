# CodeReview AI — Architecture

## Overview

```
Client (HTTP/SSE)
       │
       ▼
┌─────────────────────────────────────────────────┐
│              Spring Boot App (8080)              │
│                                                  │
│  ReviewController ──► ReviewService              │
│  AuthController   ──► AuthService                │
│                         │                        │
│                    ReviewProducer                 │
│                         │                        │
│                   RabbitMQ Queue                 │
│                         │                        │
│                   ReviewConsumer                 │
│                         │                        │
│                   ReviewProcessor                │
│                    ├── PromptBuilder             │
│                    ├── OllamaService             │
│                    └── CacheService              │
└─────────────────────────────────────────────────┘
       │           │           │           │
       ▼           ▼           ▼           ▼
  PostgreSQL     Redis      RabbitMQ    Ollama
  (history)    (cache)     (queue)    (LLM, 11434)
```

## Request Flow

### Synchronous submission (POST /api/v1/reviews)
1. JWT authenticated request arrives at `ReviewController`
2. `ReviewService` validates rate limit (20/hour) and line count (≤500)
3. `Review` persisted to PostgreSQL with status `PENDING`
4. `ReviewProducer` publishes `reviewId` to `review.queue` via RabbitMQ
5. HTTP 202 Accepted with `ticket_id` returned immediately

### Asynchronous processing
1. `ReviewConsumer` picks up message from RabbitMQ
2. `ReviewProcessor.process()` is called:
   - Sets status → `PROCESSING`
   - `CacheService` checks Redis for same code+language hash (24h TTL)
   - On cache miss: `PromptBuilder` generates language-specific prompt
   - `OllamaService` calls Ollama REST API (with retry + exponential backoff)
   - Response parsed to `LlmAnalysisResult` (retried up to 3x on invalid JSON)
   - `ReviewResult` persisted to PostgreSQL
   - Status → `COMPLETED`

### Result retrieval
- `GET /api/v1/reviews/{ticket_id}` — poll for result
- `GET /api/v1/reviews/{ticket_id}/stream` — SSE streaming (WebFlux reactive)

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Spring MVC + WebFlux mixed | SSE streaming needs reactive; the rest is simpler with MVC |
| RabbitMQ async queue | Decouples submission from slow LLM processing |
| Redis cache (SHA-256 key) | Same code = same analysis, avoids redundant LLM calls |
| Flyway migrations | Schema versioned, reproducible, no DDL surprises |
| Builder pattern on entities | Immutable-like construction, readable test setup |
| Strategy pattern in PromptBuilder | Each language gets its own optimized prompt template |
| ProblemDetail (RFC 7807) | Standardized error responses |

## Security

- JWT stateless auth (JJWT 0.12.x, HMAC-SHA256)
- BCrypt password hashing
- Rate limiting at service layer (20 reviews/hour/user)
- File upload size limited to 1MB
- GitHub URL validated via regex before fetch

## Observability

| Signal | Tool | Endpoint |
|--------|------|----------|
| Metrics | Micrometer + Prometheus | `/actuator/prometheus` |
| Health | Spring Actuator | `/actuator/health` |
| API docs | SpringDoc OpenAPI | `/swagger-ui.html` |

Custom metrics:
- `codereview.reviews.submitted` (counter, by language)
- `codereview.reviews.completed` (counter, by language)
- `codereview.reviews.failed` (counter, by reason)
- `codereview.processing.time` (timer, by language)
- `codereview.queue.active_processing` (gauge)
