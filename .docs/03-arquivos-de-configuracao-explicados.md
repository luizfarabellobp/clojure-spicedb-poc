# Estrutura do projeto e arquivos de configuração, um por um

> Artigo 3 de 6. Não é sobre SpiceDB nem ReBAC — é sobre "o que é essa
> pasta/esse arquivo aqui": um mapa de toda a árvore do projeto, e
> depois, com mais detalhe, o que cada arquivo de configuração faz, por
> que existe, e o que quebraria sem ele.

## Quem lê o quê

```mermaid
graph LR
    MK[Makefile] -->|gera| ENV[.env]
    ENV -->|lido por| DC[docker-compose.yml]
    DC -->|sobe| PG[(postgres)]
    DC -->|builda com| DF[app/Dockerfile]
    DF -->|instala deps de| DE[app/deps.edn]
    DC -->|sobe| SD[spicedb]
    PGI1[postgres/init/001_...sql] -.roda uma vez.-> PG
    PGI2[postgres/init/002_...sql] -.roda uma vez.-> PG
    CFG[app/resources/config.edn] -->|lido pela app via Aero| APP[app - Clojure]
    SCH[app/resources/schema.zed] -->|enviado ao SpiceDB no boot| SD
```

---

## Mapa completo do projeto: cada pasta e arquivo

Antes de entrar arquivo por arquivo, aqui está a árvore inteira do
repositório, com uma frase pra cada coisa. As seções seguintes deste
artigo explicam com mais detalhe os arquivos de configuração; o código
Clojure (`app/src/`) já tem seu próprio passeio guiado no artigo 2 — a
lista abaixo é só o mapa, pra você achar rápido o que procura.

```
poc-authz/
├── README.md                      → como rodar, cenários de teste, atalhos
├── CLAUDE.md                      → regras do template/empresa (fora do escopo desta POC)
├── SECURITY_GUIDE.md              → guia de segurança do template (idem)
├── .gitignore                     → o que não vai pro repositório (.env, caches, etc.)
├── Makefile                       → atalhos pra rodar tudo (ver seção abaixo)
├── docker-compose.yml             → os 4 serviços da POC (ver seção abaixo)
│
├── .docs/                         → os 5 artigos que você está lendo, mais a collection do Postman
│
├── postgres/init/                 → scripts SQL que rodam sozinhos na 1ª subida (ver seção abaixo)
│   ├── 001_create_databases.sql
│   └── 002_create_app_schema.sql
│
└── app/                           → a aplicação Clojure
    ├── Dockerfile                 → como a imagem da app é montada (ver seção abaixo)
    ├── deps.edn                   → dependências e comandos prontos (ver seção abaixo)
    ├── resources/
    │   ├── config.edn             → de onde a app lê sua configuração (ver seção abaixo)
    │   └── schema.zed             → a regra de autorização (ver seção abaixo, e artigos 1-2)
    └── src/streaming_authz/       → o código-fonte (ver detalhe abaixo)
```

### Dentro de `app/src/streaming_authz/` — pasta por pasta

- **`core.clj`** — o ponto de entrada: liga os componentes (SpiceDB,
  banco, servidor HTTP), roda a seed fixa, e sobe a aplicação.
- **`config.clj`** — lê o `config.edn` na subida (ver seção abaixo).
- **`domain/`** — a regra de negócio, sem nenhuma linha de gRPC ou SQL:
  - `authz_client.clj` — a "promessa" de autorização (o protocolo).
  - `movie_service.clj` — as perguntas de negócio (`can-view?`,
    `available-movies`).
- **`infra/spicedb/`** — quem fala de verdade com o SpiceDB:
  - `client.clj` — implementação real do protocolo, via gRPC.
  - `mapper.clj` — traduz ids do domínio pro formato que o SpiceDB
    espera.
- **`infra/db/`** — quem fala com o Postgres (database `app`):
  - `datasource_component.clj` — abre/fecha a conexão com o banco.
  - `movies_repo.clj`, `users_repo.clj` — leitura e escrita das duas
    tabelas.
- **`infra/http/`** — a camada HTTP (Pedestal):
  - `pedestal_component.clj` — sobe/derruba o servidor.
  - `routes.clj` — as rotas (`/health`, `/movies/:id/access`,
    `/available-movies`, `/relationships`).
  - `auth_interceptor.clj` — confere o token JWT antes de deixar passar.
  - `response.clj` — formata a resposta em JSON.
- **`infra/seed/`** — geração de dados de teste:
  - `bootstrap.clj` — o cenário fixo (alice, bob, os 4 filmes), roda
    sozinho toda vez que a app sobe.
  - `generator.clj` — a seed de volume (`make seed PROFILE=...`), sob
    demanda. Profiles: `small`/`medium`/`large` (amostrados),
    `massive` (produto cartesiano usuário×filme) e `chain-30` (cadeia
    de 30 planos encadeados via `inherits`, sem usuários/filmes de
    volume) — ver artigo 6 para o que cada um mede.
- **`perf/bench.clj`** — mede a latência das checagens de permissão,
  sequencial (`make bench`) ou com N threads simultâneas medindo
  throughput (`make bench-concurrent`) — ver artigo 6.
- **`dev/token.clj`** — gera um token JWT de teste (`make mint-token`);
  só existe pra facilitar teste local, não é parte da API de produção.

Isso é tudo o que existe em `app/src/` — nenhuma pasta a mais, nenhum
arquivo "utilitário" solto. Cada pasta tem uma responsabilidade só, e o
artigo 2 explica como elas conversam entre si.

---

## Na raiz do projeto

### `docker-compose.yml`

Descreve os 4 serviços da POC (`postgres`, `spicedb-migrate`, `spicedb`,
`app`), em que ordem cada um precisa subir, quais portas ficam
disponíveis no seu computador (`localhost:5433`, `:50051`, `:3000`), e
dois "espaços de guardar dados" (`poc_pg_data` — os dados do Postgres;
`m2-cache` — as dependências do Clojure, pra não baixar tudo de novo a
cada build). É esse arquivo que `make up`/`make db`/`make down`/
`make reset` usam por baixo dos panos.

### `Makefile`

Um jeito mais fácil de rodar os comandos do `docker-compose.yml` e da
aplicação, sem precisar decorar a sintaxe certa de cada um. Cada alvo
(`up`, `db`, `seed`, `bench`, `bench-concurrent`, `mint-token`, `reset`,
`logs`, `ps`) é um atalho. O alvo `env` é o único que faz algo a mais
além de atalho: ele **cria** o `.env` (explicado abaixo) na primeira vez
que você roda qualquer comando que precise dele.

### `.env` (criado sozinho, nunca vai pro repositório)

Não existe no repositório — é criado pelo `make env` (ou
automaticamente por `make up`/`make db`) na primeira vez que você roda
a POC, com duas variáveis:

```
SPICEDB_PRESHARED_KEY=<64 caracteres aleatórios>
JWT_HS256_SECRET=<64 caracteres aleatórios>
```

O Docker Compose já procura esse arquivo sozinho e repassa essas
variáveis pro container do SpiceDB (como senha de acesso) e pro
container da app (pra assinar/conferir os tokens). Toda vez que você
roda `make reset` e depois `make env`, nasce um par novo — por isso um
token gerado numa máquina não funciona em outra (ver o artigo sobre a
collection do Postman).

---

## `postgres/init/` — só roda na primeira vez

Esses dois arquivos SQL rodam sozinhos, pela própria imagem do
Postgres, mas **só na primeira vez** que o "espaço de guardar dados"
(`poc_pg_data`) é criado do zero. Rodar `make up` de novo, com os dados
já lá, não executa esses scripts outra vez. Pra forçar de novo, precisa
de `make reset` antes (que apaga tudo).

### `001_create_databases.sql`

Cria as duas databases separadas (`app`, `spicedb`) e as duas
credenciais (`app_user`, `spicedb_user`), cada uma só conseguindo
entrar na própria database — o mecanismo de isolamento já explicado nos
outros artigos.

### `002_create_app_schema.sql`

Cria as tabelas `movies` e `users`, dentro da database `app`, e dá
permissão nelas pro `app_user`. São só duas tabelas simples que quase
não mudam, por isso não tem ferramenta de migration (tipo Flyway) nesta
POC — o SQL puro já resolve.

---

## `app/` — a aplicação Clojure

### `deps.edn`

A lista de dependências da aplicação (Pedestal, cliente do SpiceDB,
`component`, Aero, `buddy-sign`, `next.jdbc`, `jsonista`) e os comandos
prontos (`:run`, `:seed`, `:bench`, `:bench-concurrent`, `:mint-token`)
que o `Makefile` e o `Dockerfile` chamam.

### `Dockerfile`

Monta a imagem da aplicação, começando de
`clojure:temurin-21-tools-deps-bookworm-slim`. Copia só o `deps.edn`
primeiro e baixa as dependências antes de copiar o resto do código —
assim, se você só mudar um arquivo `.clj`, o Docker não precisa baixar
tudo de novo. O `docker-compose.yml` também monta a pasta `./app`
direto dentro do container, então em desenvolvimento você edita o
código no seu computador e ele já reflete lá dentro, sem rebuild.

### `resources/config.edn`

O arquivo que a aplicação lê (via Aero) assim que sobe. Não tem nenhum
valor fixo escrito nele — cada linha usa `#env` pra buscar o valor de
uma variável de ambiente (as mesmas que o `docker-compose.yml` passa
pro container: `PORT`, `SPICEDB_ENDPOINT`, `SPICEDB_PRESHARED_KEY`,
`APP_DB_JDBC_URL`, `JWT_HS256_SECRET`). É o único lugar do código que
sabe o *nome* dessas variáveis — o resto da aplicação só recebe o mapa
já pronto.

### `resources/schema.zed`

O arquivo mais importante da POC — é ele que define a regra de "quem
pode ver o quê" (ver os artigos 1 e 2 pra entender o conteúdo). Fica em
`resources/` porque é lido como texto puro, na hora que a aplicação
sobe, e enviado pro SpiceDB (`WriteSchema`). Mudar esse arquivo e
reiniciar o container `app` já aplica a regra nova, sem mexer no
Postgres.

> **Dica pra quem for mexer no schema:** dá pra testar um `.zed` novo
> sem nem precisar subir esta POC, usando o
> [SpiceDB Playground](https://play.authzed.com/) — um SpiceDB de
> verdade rodando dentro do navegador, sem instalar nada. Ele deixa
> escrever schema, criar relações de teste, e ver o resultado mudando
> na hora que você edita ("Check Watches"), além de conferências
> automáticas chamadas `Assertions` e `Expected Relations` — ver
> [Validation, Testing, Debugging SpiceDB Schemas](https://authzed.com/docs/spicedb/modeling/validation-testing-debugging).
> Bom pra rascunhar uma ideia antes de trazer pra este `schema.zed` de
> verdade.

---

## O que este artigo não cobre (de propósito)

`CLAUDE.md`, `SECURITY_GUIDE.md`, `.gitignore` são regras do
template/projeto, não configuração de execução da POC. Ver esses
arquivos direto, e a Seção 5 do `CLAUDE.md`, se quiser esse contexto.

## Referências

- [SpiceDB Playground](https://play.authzed.com/) — ferramenta oficial da Authzed, sem instalação.
- [Validation, Testing, Debugging SpiceDB Schemas — Authzed Docs](https://authzed.com/docs/spicedb/modeling/validation-testing-debugging) — os três jeitos de testar um schema.
