# POC — Autorização com SpiceDB

POC para avaliar a viabilidade do **SpiceDB** como motor de autorização
(ReBAC — Relationship-Based Access Control) para conteúdos específicos de
um sistema de streaming que já roda em Postgres.

## Comece rápido (baixar, subir e testar)

```bash
git clone https://github.com/luizfarabellobp/clojure-spicedb-poc.git
cd clojure-spicedb-poc

make up
```

1. **Pré-requisitos:** só Docker instalado e **rodando** + `make` (já vem
   por padrão no macOS/Linux) — nada de Clojure/Java/Postgres na sua
   máquina, tudo roda em container. Ver detalhes na seção
   [Pré-requisitos](#pré-requisitos) abaixo.
2. `make up` builda a imagem, sobe os 4 containers na ordem certa e
   espera a aplicação responder de verdade antes de devolver o terminal
   (pode levar alguns minutos na primeira vez — as próximas são
   rápidas). Quando terminar, mostra `Aplicação pronta em
   http://localhost:3000`. Os usuários de teste `alice`/`bob` já vêm
   com planos, produtos e filmes pré-carregados — sem seed manual.
3. **Importe a collection do Postman**:
   [`.docs/spicedb-poc.postman_collection.json`](.docs/spicedb-poc.postman_collection.json)
   (Postman → *Import* → selecione o arquivo).
4. Gere os tokens de teste e cole nas variáveis da collection:
   ```bash
   make mint-token USER_ID=alice
   make mint-token USER_ID=bob
   ```
   No Postman, abra a collection → aba **Variables** → cole o token da
   alice em `alice_token` e o do bob em `bob_token` → **Save**.
5. Rode as 4 pastas da collection **em ordem** (clique com o botão
   direito na collection → *Run collection*, ou execute pasta por
   pasta). A pasta 4 escreve/altera dados — se quiser rodar tudo de novo
   do zero, `make reset && make up` antes.

Pronto — isso já cobre os cenários de plano/produto/tag/acesso direto,
Caveats (ABAC por região) e escrita de relação em runtime, todos com
`pm.test` conferindo o resultado esperado automaticamente.

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
citado com fonte no artigo 1 (link abaixo).

Esta POC testa o núcleo dessa família — ReBAC puro, para modelar planos
de assinatura, produtos avulsos e tags de conteúdo — **e também
implementa e verifica um exemplo de Caveats** (restrição por
região geográfica), com Postgres compartilhado entre os dados de negócio
da aplicação e o datastore interno do SpiceDB.

**Leitura completa, em ordem, com todas as referências e o passo a passo do código:**
1. [`.docs/01-o-que-e-spicedb-rebac-abac.md`](.docs/01-o-que-e-spicedb-rebac-abac.md) — Zanzibar, ReBAC, ABAC, arquitetura do SpiceDB e o caso Netflix, com fontes.
2. [`.docs/02-como-o-spicedb-funciona-nesta-poc.md`](.docs/02-como-o-spicedb-funciona-nesta-poc.md) — o problema que esta POC resolve, as tabelas do banco, e como cada arquivo do código se encaixa.
3. [`.docs/03-arquivos-de-configuracao-explicados.md`](.docs/03-arquivos-de-configuracao-explicados.md) — mapa de toda a estrutura do projeto (pasta por pasta) e o que cada arquivo de configuração faz (docker-compose, Makefile, deps.edn, schema.zed, etc.).
4. [`.docs/04-como-o-spicedb-guarda-dados-no-postgres.md`](.docs/04-como-o-spicedb-guarda-dados-no-postgres.md) — as tabelas internas do SpiceDB, com estrutura e exemplos de linha extraídos direto do banco local desta POC.
5. [`.docs/05-alternativas-ao-spicedb.md`](.docs/05-alternativas-ao-spicedb.md) — outras ferramentas do mesmo espaço (OpenFGA, Ory Keto, OPA, AWS Cedar, Casbin) e por que esta POC escolheu o SpiceDB.
6. [`.docs/06-testes-de-carga-e-desempenho.md`](.docs/06-testes-de-carga-e-desempenho.md) — mais casos de teste de carga/desempenho, com o passo a passo de comandos para reproduzir e os resultados reais medidos.

Além dos 5 artigos acima, [`.docs/spicedb-poc.postman_collection.json`](.docs/spicedb-poc.postman_collection.json)
é uma **ferramenta de teste**, não um artigo de leitura — por isso fica
fora da numeração (é o mesmo arquivo usado no "Comece rápido" acima).

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
- Opcional, só se for testar pelo Postman: o [Postman](https://www.postman.com/downloads/) instalado (ou o CLI `newman`, via `npx newman`).

Não precisa instalar Clojure, Java, Postgres nem SpiceDB na sua máquina —
tudo roda dentro dos containers.

## Detalhes de `make up` e outros comandos

`make up` (já usado no "Comece rápido") faz isso, sem passo manual nenhum:

1. Gera um `.env` local com secrets de desenvolvimento aleatórias (só para
   uso local — nunca reutilize esses valores em produção).
2. Builda a imagem da aplicação e baixa as imagens do Postgres/SpiceDB.
3. Sobe os containers na ordem certa (Postgres → migration do SpiceDB →
   SpiceDB → aplicação).
4. Espera a aplicação responder de verdade antes de devolver o terminal.

Outros comandos úteis:

```bash
make logs   # acompanha os logs da app em tempo real (Ctrl+C só sai do log, não para a app)
make db     # sobe só postgres + spicedb (sem a app) — útil pra inspecionar o banco pelo DBeaver/psql
make help   # lista todos os comandos disponíveis
```

## Testar pelo Postman (recomendado)

Já coberto no "Comece rápido" acima — resumindo os passos:

1. Importe [`.docs/spicedb-poc.postman_collection.json`](.docs/spicedb-poc.postman_collection.json) no Postman.
2. Gere os tokens: `make mint-token USER_ID=alice` e `make mint-token USER_ID=bob`.
3. Cole nas variáveis `alice_token`/`bob_token` da collection (aba *Variables*).
4. Rode as 4 pastas em ordem (a pasta 4 altera dados no banco).

A collection cobre health, os 5 cenários de ReBAC (plano, produto avulso,
acesso direto, herança, 401 sem token), os 3 cenários de Caveats/ABAC por
região, e a escrita de relações em runtime (com e sem Caveat) — cada
requisição já com `pm.test` conferindo status e corpo esperado. Validada
com `npx newman run` contra a API real: 16/16 requisições, 31/31
assertions, zero falhas.

## Testar via curl (referência manual)

Se preferir terminal em vez do Postman, os mesmos cenários funcionam via
`curl`:

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

### Alterar relações em runtime (também na pasta 4 da collection)

```bash
curl -s -X POST http://localhost:3000/relationships \
  -H "Authorization: Bearer $ALICE_JWT" -H "Content-Type: application/json" \
  -d '{"resource-type":"movie","resource-id":"avatar_3","relation":"direct_viewer","subject-type":"user","subject-id":"alice"}'
```

### Caveats — ABAC dentro do ReBAC (também na pasta 3 da collection)

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

Ver `.docs/02-como-o-spicedb-funciona-nesta-poc.md`, seção "Caveats na prática", para a explicação completa.

## Seed volumétrica e performance

```bash
make seed PROFILE=medium
make bench PROFILE=medium ITERATIONS=100
docker compose exec app sh -c 'cat $(ls -t target/perf-report-medium-*.edn | head -1)'
```

Profiles disponíveis: `small`, `medium`, `large`, `massive` — ver `app/src/streaming_authz/infra/seed/generator.clj`.

### Caso de teste: produto cartesiano (grafo denso, não só numeroso)

O profile `massive` não faz amostragem como os outros — ele gera o
produto cartesiano completo entre usuários e filmes (300 × 300 =
90.000 relações `direct_viewer`), pra testar o SpiceDB com um grafo
**denso**, não só grande. Escrever esse volume de uma vez esbarra num
limite real do SpiceDB (`MaximumUpdatesPerWrite`, 1000 atualizações por
chamada) — por isso `write-relationships!` quebra automaticamente em
lotes de 900.

```bash
make seed PROFILE=massive   # escreve 90.000 relações em lotes (~5s no total)

# aponta o benchmark pra um usuário/filme gerados, em vez dos fixos da seed padrão
docker compose exec app clojure -X:bench :profile :massive :iterations 50 \
  :check-resource-id '"gen-movie-0"' :multi-check-resource-id '"gen-movie-150"' \
  :check-subject-id '"gen-user-0"' :lookup-subject-id '"gen-user-0"'
```

Resultado real, medido localmente (90.014 tuplas no grafo, incluindo a
seed fixa):

| Operação | p50 | p95 | p99 |
|---|---|---|---|
| `check-permission` (uma relação `direct_viewer`) | ~1,5 ms | ~2,6 ms | ~9–157 ms* |
| `lookup-resources` (retorna 300 filmes) | ~18,5 ms | ~23,5 ms | ~55,6 ms |

\* variação alta no p99 do `check-permission` em uma das execuções — não
investigamos a fundo se é ruído do ambiente local (Docker Desktop) ou
um padrão real; registrado aqui em vez de omitido.

Achado principal: `check-permission` continua rápido mesmo com 90 mil
tuplas no grafo (bem parecido com o resultado dos profiles menores,
com poucas centenas de relações) — mas `lookup-resources` fica
visivelmente mais lento quando o **resultado** tem muitos itens (300
filmes retornados, contra 2-3 nos profiles menores). Ou seja, o custo
parece escalar mais com o tamanho da resposta do que com o tamanho
total do grafo — mas isso é uma observação de um teste local único, não
um benchmark rigoroso (sem múltiplas repetições, sem isolar variância
de ambiente).

## Resetar o ambiente

```bash
make reset   # apaga volumes (Postgres do zero, incluindo dados do SpiceDB)
```

## Sem suíte de testes no código da aplicação

Esta POC não tem testes automatizados de código (unitários, integração)
— decisão explícita registrada em `CLAUDE.md`, bloco `tdd_gate`.
Validação funcional é feita via a collection do Postman/`npx newman run`
e/ou os `curl` acima — não é uma suíte de testes da aplicação, é
verificação de comportamento de fora pra dentro.

## Regras do projeto

Este projeto segue as regras obrigatórias definidas em `CLAUDE.md` (arquitetura,
segurança, e o contexto específico desta POC).
