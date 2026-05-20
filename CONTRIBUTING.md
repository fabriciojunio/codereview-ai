# Guia de Contribuição

Obrigado por considerar contribuir com o CodeReview AI!

## Como Contribuir

### Reportando Bugs

1. Verifique se o bug já foi reportado nas [Issues](https://github.com/fabriciojunio/codereview-ai/issues)
2. Abra uma nova issue com o template de bug report
3. Inclua: versão, passos para reproduzir, comportamento esperado vs atual

### Sugerindo Melhorias

1. Abra uma issue com o label `enhancement`
2. Descreva o caso de uso e o benefício esperado
3. Aguarde feedback antes de iniciar a implementação

### Enviando Pull Requests

1. Faça fork do repositório
2. Crie uma branch: `git checkout -b feat/minha-feature`
3. Siga o padrão Conventional Commits
4. Escreva testes para novas funcionalidades
5. Garanta que `mvn test` passa sem erros
6. Abra o PR com descrição clara do que foi alterado

## Padrões de Código

- Java 21 com records e sealed classes quando apropriado
- Nomenclatura em inglês no código, comentários em português
- Cobertura mínima de testes: 70%
- Sem warnings de compilação

## Commits

Seguimos [Conventional Commits](https://www.conventionalcommits.org/pt-br/):

```
feat: adicionar suporte a TypeScript
fix: corrigir timeout na chamada ao Ollama
docs: atualizar README com exemplos de API
test: adicionar testes para ReviewService
refactor: extrair lógica de prompt para PromptBuilder
```
