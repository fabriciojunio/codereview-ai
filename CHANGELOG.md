# Changelog

Todas as mudanças notáveis neste projeto serão documentadas neste arquivo.

O formato segue [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/),
e este projeto adere ao [Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Não Lançado]

### Adicionado
- Suporte a análise de código TypeScript via prompt especializado
- Endpoint de métricas para monitoramento de análises realizadas
- Tabela de métricas no banco de dados para histórico de uso

## [1.0.0] - 2026-04-11

### Adicionado
- Análise de código via LLM local (Ollama) com modelos Mistral e CodeLlama
- Fila assíncrona com RabbitMQ para processamento de revisões
- Cache de resultados com Redis (TTL configurável)
- Autenticação JWT com registro e login de usuários
- Streaming SSE para entrega de resultados em tempo real
- Suporte inicial a Java, Python e JavaScript
- Integração com GitHub para busca de código de PRs
- Schema inicial do banco PostgreSQL com Flyway
- Configuração Docker Compose completa (Ollama, RabbitMQ, Redis, PostgreSQL)
- Documentação OpenAPI/Swagger disponível em `/swagger-ui.html`
- Prompts especializados por linguagem para maior qualidade de análise
