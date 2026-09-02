# CodeReview AI

*[Read this in English](README.en.md)*

Serviço de análise de código com modelo de linguagem rodando na própria infraestrutura. O
código submetido não sai da rede: em vez de chamar uma API de terceiro, a aplicação conversa
com um Ollama local. Devolve bugs, code smells, violação de SOLID e uma nota de 0 a 100 para
Java, Python e JavaScript.

`Java 21` · `Spring Boot 3.3` · `PostgreSQL` · `Redis` · `RabbitMQ` · `Ollama` · `Docker`

## O problema

Ferramenta de review com IA quase sempre manda o código para um servidor que não é seu. Em
empresa com contrato de confidencialidade, código de cliente ou requisito de LGPD, isso já
encerra a conversa. A alternativa é rodar o modelo dentro de casa, e aí aparece o problema
real: inferência de LLM em CPU leva de 10 a 60 segundos por arquivo. Uma requisição HTTP
síncrona não sobrevive a isso.

O projeto existe para resolver essa segunda parte. A análise em si é a parte fácil.

## Decisões de arquitetura

**A submissão não espera a análise.** `POST /reviews` grava o registro, publica na fila e
devolve um ticket em milissegundos. Quem processa é um consumidor separado. Se o modelo
demorar um minuto, o problema é do consumidor, não do cliente HTTP, e a aplicação continua
aceitando submissão enquanto isso.

**Fila em vez de `@Async`.** Uma thread pool do próprio processo seria mais simples, mas
perde o trabalho em andamento quando a aplicação cai, e é justamente quando ela cai que se
descobre que perdeu. Com RabbitMQ a mensagem sobrevive ao restart e o consumidor pode ser
escalado sem tocar na aplicação web.

**Cache pelo hash do código, não pelo id.** Rodar duas vezes o mesmo arquivo é o caso mais
comum em desenvolvimento, e é o mais caro. A chave do Redis é o conteúdo submetido, então
reenviar o mesmo código devolve na hora em vez de ocupar a GPU de novo.

**O prazo do cache varia a cada gravação.** Prazo fixo faz as chaves gravadas no mesmo lote
vencerem no mesmo segundo: uma análise em massa hoje vira, sem ninguém perceber, uma rajada de
ausências de cache exatamente 24 horas depois, todas indo para a GPU juntas. Espalhar o
vencimento em uma faixa de 10% converte o pico numa ladeira.

**Só um consumidor analisa cada código por vez.** Dez submissões iguais caindo na fila juntas
encontram o cache vazio e, sem defesa, geram dez inferências para produzir a mesma resposta.
Quem chega primeiro fica com uma reserva no Redis, gravada com `SET NX` para não existir janela
entre testar e marcar. Os outros esperam pouco, reconsultam, e analisam por conta própria se a
espera esgotar: a reserva é uma economia, não uma trava, porque o processo que a tomou pode ter
morrido.

**SSE em vez de polling.** O resultado chega por `GET /reviews/{ticket}/stream` conforme o
modelo gera. Server-Sent Events porque o fluxo é de mão única, o que dispensa o WebSocket e
todo o handshake que vem com ele.

**JWT com filtro próprio, sem sessão.** Não há estado de sessão no servidor, o que é o que
permite subir mais de uma instância atrás de um balanceador sem sticky session.

**Migração versionada com Flyway.** Nada de `ddl-auto: update`. O esquema é um artefato do
repositório, não um efeito colateral do Hibernate.

**Prompt por linguagem.** O `PromptBuilder` monta instrução diferente para Java, Python e
JavaScript, porque pedir "encontre problemas" genericamente devolve conselho genérico. Java
recebe pergunta sobre generics, recurso não fechado e SOLID; Python sobre PEP8, type hint e
argumento padrão mutável.

## Fluxo

```
POST /reviews
      │
      ▼
ReviewController ──► ReviewService ──► RabbitMQ ──┐   devolve ticketId
                          │                        │
                     (grava PENDING)               ▼
                                            ReviewConsumer
                                                   │
                                            ReviewProcessor
                                       ┌───────────┼───────────┐
                                       ▼           ▼           ▼
                                 PromptBuilder  Ollama     CacheService
                                 (por lang)     (local)    (Redis 24h)
                                                   │
                                                   ▼
                                             PostgreSQL
                                                   │
                       GET /reviews/{ticket}/stream ──► SSE para o cliente
```

## Rodando

Precisa de Docker e de uns 8 GB de RAM livres para o modelo.

```bash
docker compose up -d
docker exec -it codereview-ollama ollama pull codellama
```

O `docker-compose.yml` sobe aplicação, PostgreSQL, Redis, RabbitMQ e Ollama. Tem reserva de
GPU Nvidia declarada: sem placa, remova o bloco `deploy` e a inferência roda em CPU, mais
devagar.

```bash
# registrar e pegar o token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"dev@example.com","password":"password123"}' | jq -r .token)

# submeter
TICKET=$(curl -s -X POST http://localhost:8080/api/v1/reviews \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"language":"java","sourceCode":"public class A { }","filename":"A.java"}' | jq -r .ticketId)

# resultado
curl -s http://localhost:8080/api/v1/reviews/$TICKET -H "Authorization: Bearer $TOKEN" | jq .
```

Swagger em `http://localhost:8080/swagger-ui.html`.

## API

| Método | Rota | O que faz |
|---|---|---|
| `POST` | `/api/v1/auth/register` | Cria usuário |
| `POST` | `/api/v1/auth/login` | Devolve o JWT |
| `POST` | `/api/v1/reviews` | Submete código em texto |
| `POST` | `/api/v1/reviews/upload` | Submete arquivo |
| `POST` | `/api/v1/reviews/github` | Submete a partir de uma URL do GitHub |
| `GET` | `/api/v1/reviews/{ticketId}` | Consulta o resultado |
| `GET` | `/api/v1/reviews/{ticketId}/stream` | Acompanha por SSE |
| `GET` | `/api/v1/reviews/history` | Histórico paginado |

A resposta traz `score`, `summary`, `bugs`, `code_smells`, `solid_violations`,
`refactoring_suggestions` e `positive_aspects`, cada achado com linha, severidade e sugestão.

## Testes

129 testes em 18 classes, com o gate de cobertura travado no build. As chamadas ao Ollama são
atendidas por um MockWebServer, porque teste que depende de resposta de LLM não é determinístico,
e o teste de fluxo completo sobe a aplicação inteira sem exigir Docker instalado: o PostgreSQL é
embutido pelo próprio teste e o broker é substituído por um dublê.

```bash
cd backend
mvn test
mvn test -Dtest="*IntegrationTest"
```

O CI roda build e testes, mais CodeQL e verificação de dependência vulnerável a cada push.

## Limitações conhecidas

O modelo é a parte menos confiável do sistema, e o projeto assume isso. A nota de 0 a 100 é
uma opinião do LLM, não uma métrica calculada: serve para ordenar arquivos, não para virar
critério de merge. Achado apontado em linha errada acontece, principalmente em arquivo grande.

O `codellama` cabe em 8 GB e é o padrão porque roda na máquina da maioria das pessoas. O
`deepseek-coder` acha bem mais coisa e erra menos linha, ao custo de mais memória.

Não há retentativa com backoff no consumidor: mensagem que falha volta para a fila e pode
entrar em laço se o erro for determinístico. É o próximo item.

---

Escrito por [Fabrício Júnio](https://github.com/fabriciojunio).
