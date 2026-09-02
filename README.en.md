# CodeReview AI

*[Leia em português](README.md)*

Automated code review with a language model running on your own infrastructure. The submitted
code never leaves the network: instead of calling a third-party API, the application talks to a
local Ollama instance. It returns bugs, code smells, SOLID violations and a 0 to 100 quality
score for Java, Python and JavaScript.

`Java 21` · `Spring Boot 3.3` · `PostgreSQL` · `Redis` · `RabbitMQ` · `Ollama` · `Docker`

## The problem

AI review tools almost always ship your code to a server that is not yours. Under an NDA, with
client code, or against a data-protection requirement, that ends the conversation before it
starts. The alternative is running the model in house, and that is where the real problem shows
up: LLM inference on CPU takes 10 to 60 seconds per file. A synchronous HTTP request does not
survive that.

This project exists to solve that second part. The analysis itself is the easy half.

## Architecture decisions

**Submission does not wait for the analysis.** `POST /reviews` stores the record, publishes to
the queue and returns a ticket in milliseconds. A separate consumer does the work. If the model
takes a minute, that is the consumer's problem and not the HTTP client's, and the application
keeps accepting submissions meanwhile.

**A queue instead of `@Async`.** An in-process thread pool would be simpler, but it loses
in-flight work when the application dies, and the moment it dies is exactly when you find out.
With RabbitMQ the message survives a restart and the consumer scales without touching the web
application.

**Cache keyed by the code hash, not by id.** Running the same file twice is the most common case
in development and the most expensive one. The Redis key is the submitted content, with a 24-hour
TTL, so resubmitting the same code answers instantly instead of occupying the GPU again.

**SSE instead of polling.** The result arrives on `GET /reviews/{ticket}/stream` as the model
generates it. Server-Sent Events because the flow is one-way, which makes a WebSocket and its
handshake unnecessary.

**JWT with a custom filter, no sessions.** There is no session state on the server, which is what
allows more than one instance behind a load balancer without sticky sessions.

**Versioned migrations with Flyway.** No `ddl-auto: update`. The schema is an artifact in the
repository, not a side effect of Hibernate.

**A prompt per language.** `PromptBuilder` assembles a different instruction for Java, Python and
JavaScript, because asking "find problems" generically returns generic advice. Java gets asked
about generics, unclosed resources and SOLID; Python about PEP8, type hints and mutable default
arguments.

## Flow

```
POST /reviews
      │
      ▼
ReviewController ──► ReviewService ──► RabbitMQ ──┐   returns ticketId
                          │                        │
                    (stores PENDING)               ▼
                                            ReviewConsumer
                                                   │
                                            ReviewProcessor
                                       ┌───────────┼───────────┐
                                       ▼           ▼           ▼
                                 PromptBuilder  Ollama     CacheService
                                 (per language) (local)    (Redis, 24h)
                                                   │
                                                   ▼
                                             PostgreSQL
                                                   │
                       GET /reviews/{ticket}/stream ──► SSE to the client
```

## Running it

Needs Docker and about 8 GB of free RAM for the model.

```bash
docker compose up -d
docker exec -it codereview-ollama ollama pull codellama
```

Compose brings up the application, PostgreSQL, Redis, RabbitMQ and Ollama. An Nvidia GPU
reservation is declared: without a card, drop the `deploy` block and inference runs on CPU, more
slowly.

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"dev@example.com","password":"password123"}' | jq -r .token)

TICKET=$(curl -s -X POST http://localhost:8080/api/v1/reviews \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"language":"java","sourceCode":"public class A { }","filename":"A.java"}' | jq -r .ticketId)

curl -s http://localhost:8080/api/v1/reviews/$TICKET -H "Authorization: Bearer $TOKEN" | jq .
```

Swagger UI on `http://localhost:8080/swagger-ui.html`.

## API

| Method | Route | What it does |
|---|---|---|
| `POST` | `/api/v1/auth/register` | Creates a user |
| `POST` | `/api/v1/auth/login` | Returns the JWT |
| `POST` | `/api/v1/reviews` | Submits code as text |
| `POST` | `/api/v1/reviews/upload` | Submits a file |
| `POST` | `/api/v1/reviews/github` | Submits from a GitHub URL |
| `GET` | `/api/v1/reviews/{ticketId}` | Fetches the result |
| `GET` | `/api/v1/reviews/{ticketId}/stream` | Follows it over SSE |
| `GET` | `/api/v1/reviews/history` | Paginated history |

The response carries `score`, `summary`, `bugs`, `code_smells`, `solid_violations`,
`refactoring_suggestions` and `positive_aspects`, each finding with a line, a severity and a
suggestion.

## Tests

88 tests across 17 classes. The integration ones use Testcontainers, so real PostgreSQL and
RabbitMQ start instead of mocks, and calls to Ollama are served by a MockWebServer, because a
test that depends on an LLM response is not deterministic.

```bash
cd backend
mvn test
mvn test -Dtest="*IntegrationTest"
```

CI runs the build and tests, plus CodeQL and a vulnerable-dependency check on every push.

## Known limitations

The model is the least reliable part of the system, and the project assumes that. The 0 to 100
score is the LLM's opinion, not a computed metric: it is useful for ordering files, not as a merge
gate. Findings pointing at the wrong line happen, mostly in large files.

`codellama` fits in 8 GB and is the default because it runs on most machines. `deepseek-coder`
finds considerably more and misses fewer lines, at the cost of memory.

There is no retry with backoff in the consumer: a message that fails goes back to the queue and
can loop if the error is deterministic. That is the next item.

---

Written by [Fabrício Júnio](https://github.com/fabriciojunio).
