# POC — Autorização com SpiceDB

POC para avaliar a viabilidade do **SpiceDB** como motor de autorização
(ReBAC — Relationship-Based Access Control) para conteúdos específicos de
um sistema de streaming que já roda em Postgres.

## Em resumo: o que é o SpiceDB e por que ele está aqui

A pergunta que toda aplicação com controle de acesso precisa responder é
"este usuário pode fazer esta ação neste recurso, agora?". O jeito
tradicional é espalhar essa lógica em `if`s pelo código. O **SpiceDB** é
um banco de dados especializado, inspirado no paper **Zanzibar** do
Google, que trata essa pergunta como uma consulta a um **grafo de
relações** ("alice é assinante do plano X", "o plano X dá acesso ao filme
Y") em vez de lógica espalhada — esse modelo se chama **ReBAC**. A outra
família de solução, **ABAC** (Attribute-Based Access Control), decide
autorização avaliando atributos na hora (localização, dispositivo, plano)
em vez de relações pré-escritas. A própria **Netflix** patrocinou uma
funcionalidade do SpiceDB (chamada *Caveats*) justamente para misturar os
dois modelos, quando precisou autorizar identidades de infraestrutura
baseadas em atributos dinâmicos — um caso documentado publicamente e
citado com fonte no artigo completo.

Esta POC testa o cenário mais simples dessa mesma família: ReBAC puro,
para modelar planos de assinatura, produtos avulsos e tags de conteúdo de
um catálogo de filmes, com Postgres compartilhado entre os dados de
negócio da aplicação e o datastore interno do SpiceDB.

**Leitura completa, com todas as referências e o passo a passo do código:**
- [`.docs/o-que-e-spicedb-rebac-abac.md`](.docs/o-que-e-spicedb-rebac-abac.md) — Zanzibar, ReBAC, ABAC, arquitetura do SpiceDB e o caso Netflix, com fontes.
- [`.docs/como-o-spicedb-funciona-nesta-poc.md`](.docs/como-o-spicedb-funciona-nesta-poc.md) — o problema que esta POC resolve, as tabelas do banco, e como cada arquivo do código se encaixa.

## Arquitetura

- `postgres` — uma instância, duas databases isoladas: `app` (dados de
  negócio: `movies`, `users`) e `spicedb` (interna, gerida só pelo binário
  do SpiceDB, com role/credencial própria sem `CONNECT` cruzado).
- `spicedb` — motor de permissões (ReBAC), schema em `app/resources/schema.zed`.
- `app` — API Clojure (Pedestal), autenticação JWT HS256 local, autorização
  via SpiceDB.

## Como rodar

Requer Docker e `make`. O `.env` (com secrets de desenvolvimento) é
gerado automaticamente na primeira vez — nunca reutilize esses valores
fora de um ambiente local/isolado.

```bash
make up            # sobe a stack inteira (build incluso) e espera a app ficar pronta
make logs           # acompanha os logs da app (Ctrl+C para sair)
```

Ver `make help` para a lista completa de comandos.

## Testar os cenários de autorização

```bash
ALICE_JWT=$(make mint-token USER_ID=alice | tail -1)
BOB_JWT=$(make mint-token USER_ID=bob | tail -1)

curl -s http://localhost:3000/movies/grinch/access -H "Authorization: Bearer $ALICE_JWT"
curl -s http://localhost:3000/movies/avatar_3/access -H "Authorization: Bearer $ALICE_JWT"
curl -s http://localhost:3000/available-movies -H "Authorization: Bearer $BOB_JWT"
curl -s http://localhost:3000/movies/grinch/access   # sem token → 401
```

| Usuário | Filme | Esperado |
|---|---|---|
| alice | grinch | `allowed: true` (plan:basic) |
| alice | duro_de_matar | `allowed: true` (via produto avulso promo_natal) |
| alice | avatar_3 | `allowed: false` (sem premium, sem tag blockbuster) |
| bob | avatar_3 | `allowed: true` (direct_viewer explícito) |
| bob | grinch | `allowed: true` (plan:medium herda acesso a basic) |

## Alterar relações em runtime

```bash
curl -s -X POST http://localhost:3000/relationships \
  -H "Authorization: Bearer $ALICE_JWT" -H "Content-Type: application/json" \
  -d '{"resource-type":"movie","resource-id":"avatar_3","relation":"direct_viewer","subject-type":"user","subject-id":"alice"}'
```

## Seed volumétrica e performance

```bash
make seed PROFILE=medium
make bench PROFILE=medium ITERATIONS=100
docker compose exec app sh -c 'cat $(ls -t target/perf-report-medium-*.edn | head -1)'
```

Profiles disponíveis: `small`, `medium`, `large` — ver `app/src/streaming_authz/infra/seed/generator.clj`.

## Resetar o ambiente

```bash
make reset   # apaga volumes (Postgres do zero, incluindo dados do SpiceDB)
```

## Sem testes automatizados

Esta POC não tem suíte de testes (decisão explícita registrada em
`CLAUDE.md`, bloco `tdd_gate`) — validação é manual, via os `curl` acima.

## Regras do projeto

Este projeto segue as regras obrigatórias definidas em `CLAUDE.md` (arquitetura,
segurança, e o contexto específico desta POC).
