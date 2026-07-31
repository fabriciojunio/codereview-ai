# Changelog

Todas as mudanças notáveis neste projeto serão documentadas neste arquivo.

O formato segue [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/),
e este projeto adere ao [Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Não Lançado]

### Adicionado
- Suporte a análise de código TypeScript via prompt especializado
- Endpoint de métricas para monitoramento de análises realizadas
- Tabela de métricas no banco de dados para histórico de uso
- Gate de cobertura no build com JaCoCo: 70% de instruções e 60% de ramos.
  Abaixo disso o build reprova, então a cobertura não regride sem alguém
  decidir baixar o mínimo de propósito
- Job dedicado na CI para o teste de fluxo completo com Testcontainers. Antes
  ele estava excluído do gate e não rodava em lugar nenhum
- Resumo de cobertura no sumário da execução da CI
- 75 testes novos, concentrados na camada de segurança e nas regras de
  negócio: emissão e validação de token, filtro de autenticação, cadastro e
  login, cache, tradução de erro para HTTP, cota por hora, limite de linhas,
  dono do ticket e validação de URL do GitHub

### Corrigido
- Revisão inexistente e revisão de outro usuário respondiam **400**, quando o
  correto é **404**. A `ReviewNotFoundException` existia no projeto mas nunca
  era lançada, e o caso caía na regra genérica de argumento inválido. Os dois
  casos continuam respondendo igual de propósito, para não revelar que um
  ticket existe para quem não é dono dele

### Segurança
- Passou a existir teste cobrindo que token adulterado, assinado com outra
  chave ou expirado é recusado, e que nenhum desses caminhos popula o contexto
  de segurança
- Passou a existir teste cobrindo que a resposta de erro genérica não vaza
  mensagem interna de exceção, e que falha de autenticação não revela qual
  usuário foi tentado

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
