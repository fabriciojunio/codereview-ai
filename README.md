# CodeReview AI

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3-green?logo=springboot)
![Ollama](https://img.shields.io/badge/LLM-Ollama-black?logo=ollama)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?logo=postgresql)
![Redis](https://img.shields.io/badge/Redis-7-red?logo=redis)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.13-orange?logo=rabbitmq)
![Docker](https://img.shields.io/badge/Docker-Compose-blue?logo=docker)

Plataforma de code review automatizado com **LLM local via Ollama** — sem APIs externas, sem vazamento de dados. Detecta bugs, code smells, violações SOLID e atribui uma nota de qualidade para código **Java**, **Python** e **JavaScript**.

> Feito por [Fabrício Júnio](https://github.com/fabriciojunio)

---

## Funcionalidades

- Submissão de código via texto, upload de arquivo ou URL do GitHub
- Fila de processamento assíncrono (RabbitMQ) — receba um ticket ID, consulte o resultado
- Análise estruturada: bugs, code smells, violações SOLID, sugestões de refatoração, nota de qualidade (0-100)
- Server-Sent Events para streaming da análise em tempo real
- Cache Redis de 24h — mesmo código = resultado instantâneo
- Autenticação JWT + rate limiting (20 reviews/hora)
- Métricas Prometheus + Spring Actuator

---

## Início Rápido

### Pré-requisitos
- Docker + Docker Compose
- 8GB+ RAM (para o modelo CodeLlama)

### 1. Iniciar todos os serviços

```bash
docker compose up -d
```

Isso inicia: **App** + **PostgreSQL** + **Redis** + **RabbitMQ** + **Ollama**

### 2. Baixar o modelo LLM (apenas na primeira vez)

```bash
docker exec -it codereview-ollama ollama pull codellama
# Ou para melhores resultados:
docker exec -it codereview-ollama ollama pull deepseek-coder
```

### 3. Testar a API

```bash
# Registrar
curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Dev","email":"dev@example.com","password":"password123"}' | jq .

# Login (copie o token)
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"dev@example.com","password":"password123"}' | jq -r .token)

# Submeter código para review
TICKET=$(curl -s -X POST http://localhost:8080/api/v1/reviews \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "language": "java",
    "sourceCode": "public class UserService {\n  private List users = new ArrayList();\n  public Object getUser(int id) {\n    return users.get(id);\n  }\n}",
    "filename": "UserService.java"
  }' | jq -r .ticketId)

echo "Ticket: $TICKET"

# Consultar resultado (processamento leva 10-60s dependendo do modelo/hardware)
curl -s http://localhost:8080/api/v1/reviews/$TICKET \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

## Referência da API

Documentação interativa completa em: **http://localhost:8080/swagger-ui.html**

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| `POST` | `/api/v1/auth/register` | Registrar novo usuário |
| `POST` | `/api/v1/auth/login` | Obter token JWT |
| `POST` | `/api/v1/reviews` | Submeter código (texto) |
| `POST` | `/api/v1/reviews/upload` | Submeter código (arquivo) |
| `POST` | `/api/v1/reviews/github` | Submeter via URL do GitHub |
| `GET` | `/api/v1/reviews/{ticketId}` | Obter resultado da análise |
| `GET` | `/api/v1/reviews/{ticketId}/stream` | Streaming via SSE |
| `GET` | `/api/v1/reviews/history` | Histórico paginado |

### Exemplo de Resposta

```json
{
  "ticketId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "completed",
  "language": "java",
  "score": 52,
  "summary": "O código é funcional mas possui problemas críticos com tipos raw, potencial IndexOutOfBoundsException e violações SRP.",
  "bugs": [
    {
      "line": 3,
      "severity": "HIGH",
      "description": "users.get(id) lançará IndexOutOfBoundsException se o id estiver fora do range ou for inválido",
      "suggestion": "Valide o id, use Optional<User> como tipo de retorno e verifique os limites antes do acesso"
    }
  ],
  "code_smells": [
    {
      "line": 1,
      "type": "RAW_TYPE",
      "description": "Usando List sem parâmetro genérico (tipo raw)",
      "suggestion": "Use List<User> com tipagem genérica adequada"
    }
  ],
  "solid_violations": [
    {
      "principle": "SRP",
      "description": "UserService mistura armazenamento de dados (ArrayList) com lógica de negócio",
      "suggestion": "Separe o acesso a dados em uma interface UserRepository"
    }
  ],
  "refactoring_suggestions": [
    "Introduza uma interface UserRepository para abstração de acesso a dados",
    "Retorne Optional<User> em vez de Object para tratar usuários ausentes com segurança"
  ],
  "positive_aspects": [
    "Estrutura simples e legível"
  ],
  "submittedAt": "2026-04-11T15:00:00Z",
  "analyzedAt": "2026-04-11T15:00:45Z"
}
```

---

## Arquitetura

```
Cliente → ReviewController → ReviewService → RabbitMQ
                                                ↓
                                        ReviewConsumer
                                                ↓
                                        ReviewProcessor
                                         ├── PromptBuilder (específico por linguagem)
                                         ├── OllamaService (LLM local)
                                         └── CacheService (Redis)
                                                ↓
                                          PostgreSQL
```

Veja [docs/architecture.md](docs/architecture.md) para detalhes completos.

---

## Configuração

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `OLLAMA_MODEL` | `codellama` | Nome do modelo LLM |
| `OLLAMA_BASE_URL` | `http://ollama:11434` | Endpoint do Ollama |
| `JWT_SECRET` | *(altere!)* | Chave de assinatura JWT (Base64) |
| `JWT_EXPIRATION_MS` | `86400000` | Validade do token (24h) |

> **Aceleração por GPU**: O `docker-compose.yml` inclui reserva de GPU Nvidia. Remova a seção `deploy` se não tiver GPU (inferência por CPU é mais lenta mas funciona).

---

## Desenvolvimento

```bash
# Executar com perfil dev (logs verbosos, SQL impresso)
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Executar testes
mvn test

# Executar apenas testes de integração
mvn test -Dtest="*IntegrationTest"
```

---

## Linguagens Suportadas

| Linguagem | Verificações |
|-----------|-------------|
| Java | SOLID, Clean Code, null safety, resource leaks, generics |
| Python | PEP8, idiomas Pythônicos, type hints, defaults mutáveis |
| JavaScript | Padrões async, tratamento de promises, ES2024+ moderno |
