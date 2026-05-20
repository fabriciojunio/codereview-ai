# Arquitetura — CodeReview AI

## Visão Geral

```
┌─────────────┐    HTTP/SSE    ┌──────────────────────────────────────────┐
│   Cliente   │ ◄────────────► │           Spring Boot (WebFlux)          │
│  (Browser)  │                │                                          │
└─────────────┘                │  ┌──────────┐  ┌──────────┐  ┌────────┐ │
                               │  │   Auth   │  │  Review  │  │Metric  │ │
                               │  │Controller│  │Controller│  │Ctrl    │ │
                               │  └────┬─────┘  └────┬─────┘  └───┬────┘ │
                               │       │              │             │      │
                               │  ┌────▼─────────────▼─────────────▼────┐ │
                               │  │             Service Layer            │ │
                               │  │  AuthService │ ReviewService │ ...  │ │
                               │  └──────┬────────────────┬─────────────┘ │
                               └─────────┼────────────────┼───────────────┘
                                         │                │
              ┌──────────────────────────┼────────────────┼──────────────────┐
              │   Infraestrutura         │                │                  │
              │                          ▼                ▼                  │
              │  ┌──────────────┐  ┌──────────┐  ┌────────────┐            │
              │  │  PostgreSQL  │  │ RabbitMQ │  │   Redis    │            │
              │  │  (Flyway)    │  │  (fila)  │  │  (cache)   │            │
              │  └──────────────┘  └────┬─────┘  └────────────┘            │
              │                         │                                    │
              │                    ┌────▼─────┐                             │
              │                    │  Ollama  │                             │
              │                    │  (LLM)   │                             │
              │                    │ Mistral  │                             │
              │                    └──────────┘                             │
              └────────────────────────────────────────────────────────────┘
```

## Fluxo de Análise

1. **Submissão**: Cliente envia código (raw ou URL de PR GitHub) via `POST /api/reviews`
2. **Cache**: Sistema verifica hash do código no Redis — se hit, retorna resultado imediato
3. **Fila**: Se miss, mensagem vai para fila RabbitMQ `review.queue`
4. **Processamento**: `ReviewConsumer` consome a mensagem, chama `OllamaService`
5. **LLM**: Ollama executa o modelo Mistral com prompt especializado por linguagem
6. **SSE**: Resultado é streamado via Server-Sent Events ao cliente conectado
7. **Persistência**: Review e métricas são salvos no PostgreSQL

## Decisões de Design

| Decisão | Alternativa Considerada | Motivo da Escolha |
|---|---|---|
| WebFlux (reativo) | Spring MVC | Streaming SSE nativo, backpressure |
| Ollama local | OpenAI API | Zero custo, privacidade do código |
| RabbitMQ | Kafka | Simplicidade, fanout adequado à escala |
| Flyway | Liquibase | Menor curva, SQL puro |
| JWT stateless | Session | Escalabilidade horizontal |

## Variáveis de Ambiente

Veja [.env.example](../.env.example) para configuração completa.
