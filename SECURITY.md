# Política de Segurança

## Versões Suportadas

| Versão | Suporte de Segurança |
|--------|----------------------|
| 1.x    | ✅ Ativo              |

## Reportando Vulnerabilidades

**Não abra issues públicas para vulnerabilidades de segurança.**

Envie um e-mail para **junioad555@gmail.com** com:

- Descrição detalhada da vulnerabilidade
- Passos para reproduzir
- Impacto potencial
- Sugestão de correção (se tiver)

Você receberá uma resposta em até **72 horas**.

## Práticas de Segurança

- Segredos nunca são commitados — use `.env` local
- JWT com expiração configurável (padrão: 24h)
- Rate limiting por usuário via Redis
- Ollama roda localmente — nenhum código enviado para APIs externas
- Senhas armazenadas com BCrypt (strength 12)
