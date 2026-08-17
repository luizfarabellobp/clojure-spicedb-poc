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

Esta POC testa o núcleo dessa família — ReBAC puro, para modelar planos
de assinatura, produtos avulsos e tags de conteúdo — **e também
implementa e testa ao vivo um exemplo real de Caveats** (restrição por
região geográfica), com Postgres compartilhado entre os dados de negócio
da aplicação e o datastore interno do SpiceDB.

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

## Pré-requisitos

- [Docker](https://docs.docker.com/get-docker/) instalado e **rodando**
  (Docker Desktop no Mac/Windows, Docker Engine no Linux).
- `make` (já vem por padrão no macOS e na maioria das distribuições Linux).

Não precisa instalar Clojure, Java, Postgres nem SpiceDB na sua máquina —
tudo roda dentro dos containers.

## Como rodar do zero

```bash
git clone https://github.com/luizfarabellobp/clojure-spicedb-poc.git
cd clojure-spicedb-poc

make up
```

Isso faz tudo sozinho, sem passo manual nenhum:

1. Gera um `.env` local com secrets de desenvolvimento aleatórias (só para
   uso local — nunca reutilize esses valores em produção).
2. Builda a imagem da aplicação e baixa as imagens do Postgres/SpiceDB
   (pode levar alguns minutos na primeira vez — as próximas são rápidas).
3. Sobe os containers na ordem certa (Postgres → migration do SpiceDB →
   SpiceDB → aplicação).
4. Espera a aplicação responder de verdade antes de devolver o terminal,
   e então mostra:

   ```
   Aplicação pronta em http://localhost:3000
   ```

A partir daí, dois usuários de teste (`alice` e `bob`) já existem com
planos, produtos e filmes pré-carregados automaticamente — não é preciso
rodar nenhuma seed manual para testar os cenários abaixo.

Outros comandos úteis:

```bash
make logs   # acompanha os logs da app em tempo real (Ctrl+C só sai do log, não para a app)
make help   # lista todos os comandos disponíveis
```

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

**Collection do Postman:** [`.docs/spicedb-poc.postman_collection.json`](.docs/spicedb-poc.postman_collection.json)
tem todos esses cenários prontos (mais os de Caveats e escrita de relação),
já com os `pm.test` de cada um. Importe no Postman, gere os tokens com
`make mint-token`, cole nas variáveis `alice_token`/`bob_token` da
collection, e rode as pastas em ordem (a pasta 4 muda o estado do banco).
Validada com `npx newman run` contra a API real: 16/16 requisições, 31/31
assertions.

## Alterar relações em runtime

```bash
curl -s -X POST http://localhost:3000/relationships \
  -H "Authorization: Bearer $ALICE_JWT" -H "Content-Type: application/json" \
  -d '{"resource-type":"movie","resource-id":"avatar_3","relation":"direct_viewer","subject-type":"user","subject-id":"alice"}'
```

## Caveats (ABAC dentro do ReBAC) — atributo avaliado na hora

O filme `filme_regional` só é liberado pra `alice` dentro de uma região
(`BR`/`AR`), avaliada em tempo real a cada checagem — não é uma coluna
fixa em tabela nenhuma:

```bash
curl -s "http://localhost:3000/movies/filme_regional/access?region=BR" -H "Authorization: Bearer $ALICE_JWT"  # {"allowed":true}
curl -s "http://localhost:3000/movies/filme_regional/access?region=US" -H "Authorization: Bearer $ALICE_JWT"  # {"allowed":false}
curl -s "http://localhost:3000/movies/filme_regional/access" -H "Authorization: Bearer $ALICE_JWT"            # {"allowed":false} — sem região, nega por padrão
```

Também dá pra conceder uma relação com Caveat em runtime (não só via seed):

```bash
curl -s -X POST http://localhost:3000/relationships \
  -H "Authorization: Bearer $ALICE_JWT" -H "Content-Type: application/json" \
  -d '{"resource-type":"movie","resource-id":"filme_regional","relation":"region_locked_viewer",
       "subject-type":"user","subject-id":"bob",
       "caveat":{"name":"region_allowed","context":{"allowed_regions":["PT"]}}}'
```

Ver `.docs/como-o-spicedb-funciona-nesta-poc.md`, seção "Caveats na prática", para a explicação completa.

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
