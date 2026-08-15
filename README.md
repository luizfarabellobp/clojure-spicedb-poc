# POC — Autorização com SpiceDB

POC para avaliar a viabilidade do SpiceDB como motor de autorização (ReBAC) para
conteúdos específicos de um sistema que já roda em Postgres.

## Arquitetura

- `postgres` — uma instância, duas databases isoladas: `app` (dados de
  negócio: `movies`, `users`) e `spicedb` (interna, gerida só pelo binário
  do SpiceDB, com role/credencial própria sem `CONNECT` cruzado).
- `spicedb` — motor de permissões (ReBAC), schema em `app/resources/schema.zed`.
- `app` — API Clojure (Pedestal), autenticação JWT HS256 local, autorização
  via SpiceDB.

## Como rodar

```bash
cp .env.example .env
# editar .env: preencher SPICEDB_PRESHARED_KEY e JWT_HS256_SECRET
# (valores de desenvolvimento arbitrários — nunca reutilizar em produção)

docker compose up --build -d
docker compose logs -f app   # aguardar "streaming-authz started on port 3000"
```

## Testar os cenários de autorização

```bash
ALICE_JWT=$(docker compose exec app clojure -X:mint-token :user-id '"alice"')
BOB_JWT=$(docker compose exec app clojure -X:mint-token :user-id '"bob"')

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
docker compose exec app clojure -X:seed :profile :medium
docker compose exec app clojure -X:bench :profile :medium :iterations 100
docker compose exec app sh -c 'cat $(ls -t target/perf-report-medium-*.edn | head -1)'
```

Profiles disponíveis: `small`, `medium`, `large` — ver `app/src/streaming_authz/infra/seed/generator.clj`.

## Resetar o ambiente

```bash
docker compose down -v   # apaga volumes (Postgres do zero, incluindo dados do SpiceDB)
```

## Sem testes automatizados

Esta POC não tem suíte de testes (decisão explícita registrada em
`CLAUDE.md`, bloco `tdd_gate`) — validação é manual, via os `curl` acima.

## Regras do projeto

Este projeto segue as regras obrigatórias definidas em `CLAUDE.md` (arquitetura,
segurança, e o contexto específico desta POC).
