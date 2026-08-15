# POC — Autorização com SpiceDB

POC para avaliar a viabilidade do SpiceDB como motor de autorização (ReBAC) para
conteúdos específicos de um sistema que já roda em Postgres. Spec completa em
`.specs/poc-structure/spec.md`; plano de implementação em
`docs/superpowers/plans/2026-08-14-spicedb-poc.md`.

## Como usar

(Instruções completas de execução, testes de cenário e seeds de volume serão
adicionadas ao final da implementação — ver o plano acima para o passo a passo
atual de cada etapa.)

## Regras do projeto

Este projeto segue as regras obrigatórias definidas em `CLAUDE.md` (arquitetura,
segurança, e o contexto específico desta POC — incluindo a decisão explícita de
não ter testes automatizados, registrada no bloco `tdd_gate`).
