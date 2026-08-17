# Formato de armazenamento do SpiceDB no Postgres

> Artigo 4 de 4. Documenta a estrutura de armazenamento interna do
> SpiceDB no Postgres, com estrutura de tabela e exemplos de linha
> extraídos do banco local desta POC. Ver `02-como-o-spicedb-funciona-nesta-poc.md`
> para o contexto de implementação e `01-o-que-e-spicedb-rebac-abac.md`
> para a fundamentação teórica de ReBAC/ABAC.

## Princípios verificados

1. A aplicação não realiza leitura ou escrita direta na database
   `spicedb`; toda interação ocorre via gRPC (`WriteRelationships`,
   `WriteSchema`, `CheckPermission`, `LookupResources`). Leitura e
   escrita no Postgres subjacente são exclusivas do processo SpiceDB.
2. O formato de armazenamento é relacional simples — tabelas e linhas
   convencionais, sem estrutura de grafo no nível de banco de dados. O
   grafo é uma interpretação aplicada pelo SpiceDB sobre essas linhas,
   segundo o `schema.zed`, no momento de resolução de uma consulta.
3. O isolamento de credenciais foi reconfirmado para este artigo: uma
   tentativa de `SELECT` na database `spicedb` com a credencial
   `app_user` retorna

   ```
   FATAL:  permission denied for database "spicedb"
   DETAIL:  User does not have CONNECT privilege.
   ```

---

## Tabelas da database `spicedb`

Estrutura extraída via `\d <tabela>`
(`docker compose exec postgres psql -U postgres -d spicedb`) contra a
imagem `authzed/spicedb:latest` utilizada nesta POC. Este layout
constitui detalhe interno de implementação — não é API pública, está
sujeito a alteração entre versões, e não há documentação oficial
descrevendo cada coluna (o modelo oficialmente documentado é o
conceitual: relation tuples, namespaces — não o schema SQL literal).
Por esse motivo, a fonte citada para estrutura de tabela é a extração
direta do banco local, não documentação externa.

### `relation_tuple` — tabela central de fatos

```
      Column       |           Type           | Nullable |           Default
-------------------+--------------------------+----------+-----------------------------
 namespace         | character varying        | not null |
 object_id         | character varying        | not null |
 relation          | character varying        | not null |
 userset_namespace | character varying        | not null |
 userset_object_id | character varying        | not null |
 userset_relation  | character varying        | not null |
 caveat_name       | character varying        |          |
 caveat_context    | jsonb                    |          |
 created_xid       | xid8                     | not null | pg_current_xact_id()
 deleted_xid       | xid8                     | not null | '9223372036854775807'::xid8
 expiration        | timestamp with time zone |          |
```

Cada linha representa um fato: o objeto `namespace:object_id` mantém a
relação `relation` com o sujeito
`userset_namespace:userset_object_id`. `created_xid`/`deleted_xid`
implementam controle de versão — uma linha não é removida por `UPDATE`;
recebe `deleted_xid` e uma nova linha é inserida, permitindo leitura em
um instante consistente (mecanismo relacionado ao "New Enemy Problem"
do Zanzibar, artigo 1). O valor `9223372036854775807` representa
"sem expiração" (linha ainda vigente).

Tuplas vigentes desta POC no momento da extração, seed fixa sem
execução de seed volumétrica:

| namespace | object_id | relation | userset_namespace | userset_object_id | caveat_name |
|---|---|---|---|---|---|
| commercial_product | promo_natal | buyer | user | alice | |
| content_tag | natalinos | allowed_product | commercial_product | promo_natal | |
| movie | avatar_3 | direct_viewer | user | bob | |
| movie | avatar_3 | tag | content_tag | blockbuster | |
| movie | avatar_3 | required_plan | plan | premium | |
| movie | duro_de_matar | required_plan | plan | premium | |
| movie | duro_de_matar | tag | content_tag | natalinos | |
| movie | filme_regional | region_locked_viewer | user | alice | **region_allowed** |
| movie | grinch | tag | content_tag | natalinos | |
| movie | grinch | required_plan | plan | basic | |
| plan | basic | subscriber | user | alice | |
| plan | basic | inherits | plan | medium | |
| plan | medium | subscriber | user | bob | |
| plan | medium | inherits | plan | premium | |

A linha de `filme_regional` é a única com `caveat_name` preenchido. O
`caveat_context` correspondente, coluna `jsonb` e portanto diretamente
legível:

```json
{"allowed_regions": ["BR", "AR"]}
```

Esse é o dado estático gravado na seed
(`{:caveat {:name "region_allowed" :context {:allowed_regions ["BR" "AR"]}}}`).
O parâmetro `user_region`, que determina o resultado da avaliação (`BR`
satisfaz, `US` não satisfaz), não está presente nesta tabela — existe
apenas no momento da chamada `CheckPermission` e não é persistido.

Observação adicional: a coluna `userset_relation` não é vazia para uma
referência direta a objeto — assume o valor literal `...` (confirmado
com `psql -x`, eliminando hipótese de truncamento de exibição). Isso é
consistente com a convenção Zanzibar/SpiceDB para indicar referência
direta a objeto, em oposição a um "userset" (`objeto#relação`, por
exemplo `group:eng#member`). Não foi localizada documentação oficial
descrevendo esse valor especificamente; o dado é reportado aqui como
observação direta do banco, não como citação de fonte externa.

### `caveat` — definição compilada de cada Caveat

```
       Column        |       Type        | Nullable |
---------------------+-------------------+----------+
 name                | character varying | not null |
 definition          | bytea             | not null |
 created_transaction | bigint            |          |
 deleted_transaction | bigint            |          |
 created_xid         | xid8              | not null |
 deleted_xid         | xid8              | not null |
```

`definition` é binária (`bytea`), não textual. Extração de
`region_allowed` (733 bytes; trecho abaixo obtido por tentativa de
leitura como texto, ilustrando a ausência de legibilidade direta):

```
\x0Eregion_allowed\x12\305\x01\x12\x0Eregion_allowed
\262\x01\x12\x11\x08\x01\x12\r\x0Buser_region\x12\r\x08\x02\x12
\x1A\x07in_list\x12\x15\x08\x03\x12\x11\x0Fallowed_regions...
```

Trata-se da árvore sintática compilada da expressão CEL `user_region in
allowed_regions` — fragmentos como `region_allowed`, `user_region`,
`in_list` e `allowed_regions` são identificáveis no binário, sem
constituir código-fonte legível. O `.zed` é compilado uma vez, na
chamada `WriteSchema`; a forma persistida é binária, favorecendo
velocidade de avaliação em `CheckPermission` em detrimento de
reversibilidade direta ao texto original.

### `namespace_config` — definição compilada de cada `definition`

```
      Column       |       Type        | Nullable |
-------------------+-------------------+----------+
 namespace         | character varying | not null |
 serialized_config | bytea             | not null |
 created_xid       | xid8              | not null |
 deleted_xid       | xid8              | not null |
```

Uma linha por `definition` do `schema.zed` (`user`, `plan`,
`commercial_product`, `content_tag`, `movie`), serializada em protobuf.
Tamanhos extraídos:

| namespace | tamanho (bytes) |
|---|---|
| user | 8 |
| plan | 382 |
| commercial_product | 346 |
| content_tag | 445 |
| movie | 715 |

`movie` apresenta o maior tamanho por possuir o maior número de
relações e a permissão de maior complexidade (`view`, quatro caminhos).

### `schema` e `schema_revision` — versionamento do schema ativo

`schema_revision` armazena um hash da revisão corrente do schema. A
tabela `schema`, destinada por design ao texto do schema em blocos
(`chunk_data`), encontrava-se vazia na instância examinada — a
compilação reside em `namespace_config`/`caveat`, sem duplicação nesta
tabela. Esta é uma observação pontual desta versão/modo de operação, não
uma generalização para todas as versões do SpiceDB.

### `relation_tuple_transaction` e `relationship_counter`

`relation_tuple_transaction` registra um snapshot Postgres
(`pg_snapshot`) por transação de escrita — mecanismo de consistência
que permite reconstruir o estado das permissões em um instante
específico, base do `ZedToken` (artigo 1). `relationship_counter`
armazena contagens pré-computadas, não exercitadas por esta POC.

### `metadata` e `alembic_version`

`metadata` contém uma linha única com identificador da instância de
datastore (extraído: `73b3aa3b-8ae0-44bd-b37d-52932b8ff7ff`; alterado a
cada `make reset`, dado que o volume é recriado). `alembic_version`
registra a migration aplicada. O nome "Alembic" corresponde
historicamente a uma ferramenta de migration do ecossistema Python; sua
presença como nome de tabela em um projeto Go sugere herança de
convenção, não confirmada além do nome observado.

---

## Tabelas da database `app`

Estrutura consideravelmente mais simples — sem colunas `bytea`, sem
controle de transação próprio.

### `movies`

```
      Column      |  Type   | Nullable |
------------------+---------+----------+
 id               | text    | not null |
 title            | text    | not null |
 synopsis         | text    |          |
 genre            | text    |          |
 release_year     | integer |          |
 duration_minutes | integer |          |
```

```
       id       |      title      | genre              | release_year | duration_minutes
----------------+-----------------+--------------------+--------------+------------------
 avatar_3       | Avatar 3        | Ficção Científica  |         2025 |              190
 duro_de_matar  | Duro de Matar   | Ação               |         1988 |              132
 filme_regional | Retratos do Sul | Documentário       |         2023 |               75
 grinch         | O Grinch        | Comédia            |         2018 |               86
```

### `users`

```
    Column    |           Type           | Nullable | Default
--------------+--------------------------+----------+---------
 id           | text                     | not null |
 email        | text                     | not null |
 display_name | text                     | not null |
 country      | text                     |          |
 created_at   | timestamp with time zone | not null | now()
```

```
  id   |       email       | display_name | country |          created_at
-------+-------------------+--------------+---------+-------------------------------
 alice | alice@example.com | Alice        | BR      | 2026-08-15 13:25:39.043753+00
 bob   | bob@example.com   | Bob          | US      | 2026-08-15 13:25:39.047467+00
```

Nenhuma coluna representa autorização — o conteúdo é descritivo, de
catálogo e de perfil. `country` é dado de cadastro, não consultado por
nenhuma checagem de autorização; o exemplo de Caveat descrito em
`02-como-o-spicedb-funciona-nesta-poc.md` recebe a região como
parâmetro de requisição, não a partir desta coluna.

---

## Referências

- Extração direta do banco local desta POC, em 2026-08-17, contra
  `authzed/spicedb:latest` — fonte primária de todo o layout de tabela e
  exemplos de linha deste artigo.
- [Datastore Migrations — Authzed Docs](https://authzed.com/docs/spicedb/concepts/datastore-migrations) — versionamento do schema interno do SpiceDB.
- [`pkg/cmd/migrate.go` — repositório `authzed/spicedb`](https://github.com/authzed/spicedb/blob/main/pkg/cmd/migrate.go) — código-fonte de criação/migração dessas tabelas.
- [Repositório `authzed/spicedb`](https://github.com/authzed/spicedb) — código-fonte completo, incluindo migrations por engine (`postgres`, `mysql`, `cockroachdb`, `spanner`).
- [Conceitos: relation tuples e Zanzibar — Authzed Docs](https://authzed.com/docs/spicedb/concepts/zanzibar) — modelo conceitual implementado por `relation_tuple`.
