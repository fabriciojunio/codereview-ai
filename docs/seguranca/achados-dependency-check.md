# Achados do Dependency-Check: fechamento

Registro de 30/10/2026. A lista completa, alerta por alerta, está em
[`achados-dependency-check.csv`](achados-dependency-check.csv).

## O que eram

O OWASP Dependency-Check rodou na CI até 26/10 e deixou 314 alertas abertos no code scanning do
GitHub, sobre 200 CVEs distintos, todos em bibliotecas do Spring Boot 3.3:

| Severidade | Alertas |
|---|---|
| crítica | 40 |
| alta | 122 |
| média | 133 |
| baixa | 11 |
| aviso | 8 |

Os componentes com mais alertas eram `netty-transport` 4.1.122, `tomcat-embed-core` 10.1.42,
`spring-web` e `spring-core` 6.1.21 e `spring-security` 6.3.10.

## Como foram corrigidos

O Spring Boot 3.3 deixou de receber correção em junho. A migração para o Spring Boot 4.1.2 (PR #2,
em produção em 30/10/2026 às 03:04) trocou todas essas bibliotecas:

| Componente | Antes | Depois |
|---|---|---|
| spring-web, spring-core | 6.1.21 | 7.0.10 |
| tomcat-embed-core | 10.1.42 | 11.0.27 |
| spring-security-web | 6.3.10 | 7.1.2 |
| netty | 4.1.122 | 4.2.19 |

No mesmo PR o Dependency-Check saiu da CI: ele dependia da base do NVD, que exige chave e
travava a pipeline. Entrou o OSV-Scanner sobre o SBOM gerado pelo próprio Maven.

Conferência em 30/10: SBOM CycloneDX gerado da branch `produção` e OSV-Scanner sobre ele, sem
nenhuma vulnerabilidade.

## Por que os alertas foram apagados, e não dispensados

Sem a ferramenta rodando, o GitHub mantém os alertas abertos para sempre. Dispensar exige escolher
entre "falso positivo", "não vai ser corrigido" e "usado em teste", e nenhum desses é verdade:
eles foram corrigidos. Gravar uma análise vazia em nome do Dependency-Check também não, porque
seria registrar uma varredura que não aconteceu. As análises da ferramenta aposentada foram
apagadas, e este registro guarda o histórico.

## Tempo até corrigir

Os alertas abriram entre 12/10 e 26/10. A mediana até a correção foi de 17,4 dias em todas as
severidades. Para crítica e alta, isso passa do prazo que eu considero aceitável (7 e 30 dias).
O motivo foi a dependência travada no Boot 3.3; a lição está no
[painel do programa](https://github.com/fabriciojunio/feira-do-comando/blob/produ%C3%A7%C3%A3o/docs/seguranca/metricas.md):
versão de framework sem suporte vira fila de crítica.
