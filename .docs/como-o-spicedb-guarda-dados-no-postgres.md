# Como o SpiceDB guarda dados no Postgres (com exemplos reais desta POC)

> Este artigo responde de forma concreta a uma pergunta que já discutimos
> na prática: o SpiceDB é o "intérprete" dos dados que ele mesmo salva no
> Postgres, certo? Sim — e aqui está exatamente o que isso significa,
> tabela por tabela, com linhas reais extraídas do banco desta POC (não
> inventadas). Ver `como-o-spicedb-funciona-nesta-poc.md` para o contexto
> geral e `o-que-e-spicedb-rebac-abac.md` para a teoria de ReBAC/ABAC.

## O princípio, confirmado de novo antes de entrar nos detalhes

1. **Nossa aplicação nunca lê nem escreve direto na database `spicedb`.**
   Ela fala gRPC com o processo do SpiceDB (`WriteRelationships`,
   `WriteSchema`, `CheckPermission`, `LookupResources`). Quem lê e
   escreve o Postgres é exclusivamente o próprio SpiceDB.
2. **O formato de armazenamento é relacional simples — tabelas e linhas
   comuns, sem nada de "estrutura de grafo" no banco em si.** O grafo é
   uma interpretação que o SpiceDB aplica em cima dessas linhas, guiada
   pelo `schema.zed`, no momento de responder uma pergunta. Isso fica bem
   claro nos exemplos abaixo.
3. Confirmado de novo, ao vivo, pra este artigo: `app_user` (a credencial
   da nossa aplicação) tenta um `SELECT` na database `spicedb` e recebe:

   ```
   FATAL:  permission denied for database "spicedb"
   DETAIL:  User does not have CONNECT privilege.
   ```

   Isolamento real, não só documentado.

---

## As tabelas da database `spicedb`

Estrutura extraída via `\d <tabela>` rodando localmente
(`docker compose exec postgres psql -U postgres -d spicedb`), contra a
imagem `authzed/spicedb:latest` usada nesta POC. **Aviso importante:**
esse layout de tabelas é detalhe interno de implementação do SpiceDB —
não é uma API pública, pode mudar entre versões, e não existe uma página
de documentação oficial descrevendo essas colunas uma a uma (o que é
oficialmente documentado é o modelo conceitual — "relation tuples",
"namespaces" — não o schema SQL literal). Por isso este artigo cita como
fonte a extração direta do banco local, não uma doc externa, para tudo
que é estrutura de tabela.

### `relation_tuple` — a tabela central: os fatos, um por linha

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

Cada linha é um fato isolado: "o objeto `namespace:object_id` tem a
relação `relation` com o sujeito `userset_namespace:userset_object_id`".
`created_xid`/`deleted_xid` são o controle de versão — uma linha nunca é
apagada de fato (`UPDATE`), ela ganha um `deleted_xid` e uma linha nova é
inserida; isso é o que dá ao SpiceDB a leitura em um instante consistente
no tempo (o mesmo princípio do "New Enemy Problem" do Zanzibar, citado no
outro artigo). `9223372036854775807` é o valor "infinito" (ainda viva).

**As 14 tuplas vivas desta POC agora, extraídas direto do banco** (seed
fixa, sem seed volumétrica rodada):

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
`caveat_context` dela, extraído em formato JSON de verdade (é uma coluna
`jsonb`, então isso é diretamente legível, ao contrário do resto):

```json
{"allowed_regions": ["BR", "AR"]}
```

Esse é exatamente o dado estático que gravamos na seed
(`{:caveat {:name "region_allowed" :context {:allowed_regions ["BR" "AR"]}}}`)
— o `user_region` que decide o resultado (`BR` permite, `US` nega) **não
está em lugar nenhum desta tabela**, porque ele só existe no momento da
chamada `CheckPermission`, nunca é persistido.

**Um detalhe curioso, visto direto no banco:** a coluna `userset_relation`
não vem vazia (`''`) para uma referência direta a um objeto — vem com o
valor literal `...` (três pontos, uma string de verdade, não truncamento
de exibição — conferido com `psql -x` pra eliminar essa dúvida). Isso
bate com a convenção do próprio Zanzibar/SpiceDB para dizer "esta
referência aponta direto para o objeto, sem passar por outra relação"
(em contraste com um "userset", que apontaria para `objeto#relação`, tipo
`group:eng#member`). Não achei uma página da documentação oficial
descrevendo esse símbolo especificamente, então registro isso aqui como
observação direta do banco, não como citação de doc externa.

### `caveat` — a definição de cada Caveat, compilada (não é texto)

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

`definition` é `bytea` — binário, não texto. Extraindo a nossa
`region_allowed` real (`length` = 733 bytes; abaixo, um recorte da
tentativa de ler como texto, pra mostrar que não é legível de verdade):

```
\x0Eregion_allowed\x12\305\x01\x12\x0Eregion_allowed
\262\x01\x12\x11\x08\x01\x12\r\x0Buser_region\x12\r\x08\x02\x12
\x1A\x07in_list\x12\x15\x08\x03\x12\x11\x0Fallowed_regions...
```

É a árvore sintática (AST) da expressão CEL `user_region in
allowed_regions` já compilada — dá pra reconhecer fragmentos como
`region_allowed`, `user_region`, `in_list`, `allowed_regions` embutidos
no binário, mas não dá pra ler isso como código-fonte. O `.zed` que a
gente escreve é compilado uma vez, na chamada `WriteSchema`, e o que fica
persistido é essa forma binária — mais rápida de avaliar a cada
`CheckPermission`, mas sem volta fácil para o texto original.

### `namespace_config` — a definição de cada `definition`/`caveat`, compilada

```
      Column       |       Type        | Nullable |
-------------------+-------------------+----------+
 namespace         | character varying | not null |
 serialized_config | bytea             | not null |
 created_xid       | xid8              | not null |
 deleted_xid       | xid8              | not null |
```

Mesma lógica do `caveat`: uma linha por `definition` do nosso
`schema.zed` (`user`, `plan`, `commercial_product`, `content_tag`,
`movie`), cada uma como protobuf serializado. Tamanhos reais extraídos:

| namespace | tamanho (bytes) |
|---|---|
| user | 8 |
| plan | 382 |
| commercial_product | 346 |
| content_tag | 445 |
| movie | 715 |

`movie` é a maior porque é a `definition` com mais relações e a
permissão mais complexa (`view`, com quatro caminhos).

### `schema` e `schema_revision` — versionamento do schema ativo

`schema_revision` guarda um hash da revisão "current" do schema.
`schema` teria, por design, o texto do schema em chunks (`chunk_data`) —
mas, extraído direto desta instância, a tabela está **vazia** nesta
versão/modo de operação (a compilação vive em `namespace_config`/
`caveat`, não duplicada aqui). Registro isso como observação honesta, não
como afirmação genérica sobre todas as versões do SpiceDB.

### `relation_tuple_transaction` e `relationship_counter` — controle interno

`relation_tuple_transaction` guarda um snapshot Postgres (`pg_snapshot`)
por transação de escrita — é o mecanismo de consistência que permite ao
SpiceDB responder "qual era o estado das permissões exatamente neste
momento", suportando o `ZedToken` citado no outro artigo.
`relationship_counter` guarda contagens pré-computadas (não usadas por
esta POC, mas parte do motor).

### `metadata` e `alembic_version` — identidade e versão da instância

`metadata` tem uma única linha, com um UUID que identifica essa instância
de datastore (extraído: `73b3aa3b-8ae0-44bd-b37d-52932b8ff7ff` — muda a
cada `make reset`, já que o volume é recriado). `alembic_version` guarda
qual migration está aplicada (o "Alembic" é a ferramenta de migration que
o SpiceDB usa por baixo, mesmo sendo um projeto Go — Alembic
historicamente é uma ferramenta do ecossistema Python, o que sugere que
esse nome de tabela é herdado de uma convenção, não necessariamente da
ferramenta em si; não confirmei isso além do nome da tabela).

---

## Para contraste: as tabelas da database `app`

Bem mais simples — sem `bytea`, sem controle de transação próprio, sem
nada especial. É por isso que a separação de databases faz sentido: dois
mundos com necessidades completamente diferentes, um do lado do outro.

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
 alice | alice@example.com | Alice        | BR      | 2026-08-17 13:25:39.043753+00
 bob   | bob@example.com   | Bob          | US      | 2026-08-17 13:25:39.047467+00
```

Nenhuma coluna aqui guarda "quem pode ver o quê" — isso é só descrição de
catálogo e de perfil. `country`, por exemplo, é dado de perfil comum,
não é lido automaticamente por nenhuma checagem de autorização (o
exemplo de Caveat usa a região vinda da requisição, não esta coluna — ver
`como-o-spicedb-funciona-nesta-poc.md`).

---

## Referências

- Extração direta do banco local desta POC, em 2026-08-17, contra
  `authzed/spicedb:latest` — fonte primária de todo o layout de tabela e
  todos os exemplos de linha deste artigo.
- [Datastore Migrations — Authzed Docs](https://authzed.com/docs/spicedb/concepts/datastore-migrations) — conceito oficial de como o SpiceDB versiona seu próprio schema interno.
- [`pkg/cmd/migrate.go` — repositório `authzed/spicedb`](https://github.com/authzed/spicedb/blob/main/pkg/cmd/migrate.go) — código-fonte real que cria/migra essas tabelas.
- [Repositório `authzed/spicedb`](https://github.com/authzed/spicedb) — código-fonte completo, incluindo os arquivos de migration por engine (`postgres`, `mysql`, `cockroachdb`, `spanner`).
- [Conceitos: relation tuples e Zanzibar — Authzed Docs](https://authzed.com/docs/spicedb/concepts/zanzibar) — o modelo conceitual (já citado em `o-que-e-spicedb-rebac-abac.md`) que a tabela `relation_tuple` implementa.
