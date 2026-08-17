# Como o SpiceDB guarda dados no Postgres (com exemplos reais desta POC)

> Artigo 4 de 5. O SpiceDB é o "intérprete" dos dados que ele mesmo
> guarda no Postgres — aqui está exatamente o que isso significa,
> tabela por tabela, com linhas de verdade tiradas do banco desta POC
> (não inventadas). Ver `02-como-o-spicedb-funciona-nesta-poc.md` pro
> contexto geral, `01-o-que-e-spicedb-rebac-abac.md` pra teoria de
> ReBAC/ABAC, e `spicedb-poc.postman_collection.json` pra testar tudo
> isso na prática.

## Três coisas pra lembrar antes de entrar nos detalhes

1. **Nossa aplicação nunca lê nem escreve direto na database
   `spicedb`.** Ela conversa por gRPC com o processo do SpiceDB
   (`WriteRelationships`, `WriteSchema`, `CheckPermission`,
   `LookupResources`). Quem lê e escreve o Postgres é só o próprio
   SpiceDB.
2. **O jeito de guardar é uma tabela relacional comum — sem nada de
   "grafo" dentro do banco em si.** O grafo é uma forma de olhar pros
   dados que o SpiceDB aplica em cima dessas linhas, seguindo o
   `schema.zed`, na hora de responder uma pergunta. Fica claro nos
   exemplos abaixo.
3. Conferimos de novo, pra este artigo: a credencial da aplicação
   (`app_user`) tenta um `SELECT` na database `spicedb` e recebe:

   ```
   FATAL:  permission denied for database "spicedb"
   DETAIL:  User does not have CONNECT privilege.
   ```

   O isolamento é real, não só uma promessa no papel.

---

## As tabelas da database `spicedb`

Essas tabelas foram olhadas direto no banco (`\d <tabela>`, rodando
`docker compose exec postgres psql -U postgres -d spicedb`), na imagem
`authzed/spicedb:latest` que esta POC usa. **Aviso:** esse layout é
detalhe interno do SpiceDB — pode mudar de versão pra versão, e não tem
uma página oficial explicando coluna por coluna (o que é documentado
oficialmente é a ideia geral — "relation tuples", "namespaces" — não o
SQL exato). Por isso, aqui a fonte é o banco local, não uma doc
externa.

### `relation_tuple` — a tabela principal: os fatos, um por linha

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

Cada linha é um fato: "o `namespace:object_id` tem a relação `relation`
com o `userset_namespace:userset_object_id`". `created_xid`/
`deleted_xid` fazem o controle de versão — uma linha nunca é apagada de
fato, ela ganha um `deleted_xid` e nasce uma linha nova. É assim que o
SpiceDB consegue responder "como estava a permissão exatamente nesse
momento" (o mesmo problema do "New Enemy Problem" do Zanzibar, artigo
1). O número `9223372036854775807` é o "infinito" (linha ainda viva).

**As 14 tuplas vivas desta POC agora**, direto do banco (seed fixa, sem
rodar a seed de volume):

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

A linha do `filme_regional` é a única com `caveat_name` preenchido. O
`caveat_context` dela, direto do banco (é uma coluna `jsonb`, então dá
pra ler sem problema):

```json
{"allowed_regions": ["BR", "AR"]}
```

Esse é o dado fixo que gravamos na seed
(`{:caveat {:name "region_allowed" :context {:allowed_regions ["BR" "AR"]}}}`).
O `user_region` que decide o resultado (`BR` permite, `US` nega) **não
está guardado em lugar nenhum desta tabela** — ele só existe na hora da
pergunta `CheckPermission`.

**Um detalhe visto direto no banco:** a coluna `userset_relation` não
vem vazia pra uma referência direta a um objeto — vem com o valor
literal `...` (três pontos, uma string de verdade, não corte de tela —
conferimos com `psql -x` pra ter certeza). Isso bate com a convenção do
Zanzibar/SpiceDB pra dizer "esta referência aponta direto pro objeto,
sem passar por outra relação" (diferente de um "userset", que apontaria
pra `objeto#relação`, tipo `group:eng#member`). Não achamos uma página
oficial explicando esse detalhe — por isso registramos só como algo que
vimos no banco, não como citação de documentação.

### `caveat` — a definição de cada Caveat, já compilada

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

`definition` é binário (`bytea`), não texto. Tirando a nossa
`region_allowed` do banco (733 bytes; abaixo, um pedaço tentando ler
como texto, pra mostrar que não dá pra ler de verdade):

```
\x0Eregion_allowed\x12\305\x01\x12\x0Eregion_allowed
\262\x01\x12\x11\x08\x01\x12\r\x0Buser_region\x12\r\x08\x02\x12
\x1A\x07in_list\x12\x15\x08\x03\x12\x11\x0Fallowed_regions...
```

É a expressão CEL `user_region in allowed_regions` já compilada — dá
pra reconhecer pedaços como `region_allowed`, `user_region`, `in_list`
misturados no binário, mas não dá pra ler como código de verdade. O
`.zed` que escrevemos é compilado uma vez, quando mandamos pro SpiceDB
(`WriteSchema`), e o que fica guardado é essa forma binária — mais
rápida de conferir a cada pergunta, mas sem volta fácil pro texto
original.

### `namespace_config` — a definição de cada tipo, também compilada

```
      Column       |       Type        | Nullable |
-------------------+-------------------+----------+
 namespace         | character varying | not null |
 serialized_config | bytea             | not null |
 created_xid       | xid8              | not null |
 deleted_xid       | xid8              | not null |
```

Uma linha por tipo do nosso `schema.zed` (`user`, `plan`,
`commercial_product`, `content_tag`, `movie`), cada uma binária.
Tamanhos reais:

| namespace | tamanho (bytes) |
|---|---|
| user | 8 |
| plan | 382 |
| commercial_product | 346 |
| content_tag | 445 |
| movie | 715 |

`movie` é o maior porque é o tipo com mais relações e a permissão mais
complexa (`view`, com quatro caminhos).

### `schema` e `schema_revision` — versão do schema ativo

`schema_revision` guarda um identificador da versão atual do schema.
`schema` deveria guardar o texto do schema, em pedaços — mas, olhando
direto nesta instância, a tabela estava **vazia** (a versão compilada
mora em `namespace_config`/`caveat`, não duplicada aqui). Registramos
isso como o que vimos nesta versão específica, não como regra geral do
SpiceDB.

### `relation_tuple_transaction` e `relationship_counter` — controle interno

`relation_tuple_transaction` guarda uma "foto" do banco (`pg_snapshot`)
a cada escrita — é o mecanismo que permite ao SpiceDB responder "como
era a permissão exatamente neste momento", a base do `ZedToken` (artigo
1). `relationship_counter` guarda contagens já calculadas, que esta POC
não usa.

### `metadata` e `alembic_version` — identidade e versão da instância

`metadata` tem uma única linha, com um código que identifica essa
instância (visto no banco:
`73b3aa3b-8ae0-44bd-b37d-52932b8ff7ff` — muda a cada `make reset`, já
que os dados são recriados do zero). `alembic_version` guarda qual
migration está aplicada (o nome "Alembic" normalmente é de uma
ferramenta do mundo Python — aparecer aqui, num projeto em Go, sugere
que o nome foi herdado de alguma convenção; não confirmamos além disso).

---

## Pra comparar: as tabelas da database `app`

Bem mais simples — sem binário, sem controle de transação próprio.

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

Nenhuma coluna aqui guarda "quem pode ver o quê" — é só descrição de
catálogo e de perfil. `country`, por exemplo, é um dado de cadastro
comum, e nenhuma checagem de autorização olha pra ele (o exemplo de
Caveat usa a região vinda da requisição, não esta coluna — ver o artigo
2).

---

## Referências

- Extração direta do banco local desta POC, em 17/08/2026, na imagem
  `authzed/spicedb:latest` — fonte de todo o layout de tabela e todos
  os exemplos de linha deste artigo.
- [Datastore Migrations — Authzed Docs](https://authzed.com/docs/spicedb/concepts/datastore-migrations) — como o SpiceDB versiona o próprio schema interno.
- [`pkg/cmd/migrate.go` — repositório `authzed/spicedb`](https://github.com/authzed/spicedb/blob/main/pkg/cmd/migrate.go) — o código que cria/migra essas tabelas.
- [Repositório `authzed/spicedb`](https://github.com/authzed/spicedb) — código-fonte completo, incluindo as migrations de cada banco (`postgres`, `mysql`, `cockroachdb`, `spanner`).
- [Conceitos: relation tuples e Zanzibar — Authzed Docs](https://authzed.com/docs/spicedb/concepts/zanzibar) — a ideia geral (já citada no artigo 1) que a tabela `relation_tuple` implementa.
