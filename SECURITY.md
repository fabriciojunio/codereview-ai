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

- Segredos nunca são commitados: use `.env` local; `JWT_SECRET` é obrigatório via variável de ambiente em produção
- JWT com expiração configurável (padrão: 24h) e chave HMAC de no mínimo 256 bits
- Autorização deny-by-default: todo endpoint exige autenticação, exceto login/registro, health e Swagger
- Requisições não autenticadas recebem 401 (ProblemDetail RFC 7807), não 403
- Rate limiting por usuário (padrão: 20 reviews/hora)
- Senhas armazenadas com BCrypt (strength 12)
- Cabeçalhos de segurança aplicados: HSTS, X-Content-Type-Options, X-Frame-Options DENY, Referrer-Policy e Content-Security-Policy restritiva
- CORS negado por padrão; libere origens explicitamente via `SECURITY_CORS_ALLOWED_ORIGINS`
- Cache Redis com serialização tipada (sem default typing), evitando desserialização insegura via gadgets
- Ollama roda localmente: nenhum código enviado para APIs externas
- Análise de dependências vulneráveis (OWASP Dependency-Check) e CodeQL executadas na CI

## Verificação de Dependências

```bash
cd backend
mvn -Psecurity verify   # falha o build em CVEs com CVSS >= 7.0
```

Defina `NVD_API_KEY` no ambiente para acelerar o download da base da NVD.
