# Como o SpiceDB funciona nesta POC

> Artigo 2 de 2. O primeiro (`o-que-e-spicedb-rebac-abac.md`) explica os
> conceitos gerais — ReBAC, ABAC, Zanzibar, o caso da Netflix. Este aqui
> é o "olha o motor por dentro": explica exatamente o que acontece nesta
> POC específica, arquivo por arquivo, tabela por tabela.

## O problema que esta POC tenta resolver

Imagine um serviço de streaming. Ele já tem um banco de dados com usuários e
filmes — isso nunca foi problema. O problema é outra pergunta, que parece
simples mas não é: **"este usuário específico pode assistir a este filme
específico, agora?"**

Hoje, a resposta a essa pergunta normalmente mora espalhada pelo código:
um `if` aqui checando o plano do usuário, outro ali checando se ele
comprou aquele filme avulso, outro checando se é um filme liberado por
uma promoção sazonal, outro ainda para o caso especial de alguém que
ganhou acesso de cortesia. Cada regra nova de acesso vira mais um `if`
espalhado, e ninguém consegue responder com confiança "quem, hoje, pode
ver o quê" sem ler (e entender) todo esse código.

Essa POC existe para testar uma resposta diferente: **e se "quem pode
acessar o quê" não fosse uma pergunta que o código respondesse
calculando na hora, e sim uma pergunta que um banco de dados
especializado em relações já soubesse responder, porque essas relações
estão todas escritas nele, de um jeito só?**

Esse banco especializado é o SpiceDB. A pergunta que a POC precisa
responder não é "SpiceDB funciona tecnicamente" (isso qualquer
documentação garante) — é: **esse jeito de modelar autorização como um
grafo de relações é mais claro, mais seguro e mais fácil de evoluir do
que a alternativa de espalhar `if`s pelo código?** O resto deste artigo
mostra como a POC foi montada para responder isso.

---

## O "elenco de personagens" desta POC

Antes do código, vale nomear as peças, porque elas se repetem em toda
explicação daqui pra frente:

- **`user`** — uma pessoa (`alice`, `bob`).
- **`plan`** — um plano de assinatura (`basic`, `medium`, `premium`),
  organizados em ordem crescente de acesso.
- **`commercial_product`** — algo que se compra avulso, fora do plano
  (ex.: uma promoção de Natal).
- **`content_tag`** — uma etiqueta de conteúdo (ex.: "filmes natalinos"),
  que pode ser liberada por mais de um caminho.
- **`movie`** — o recurso final que alguém quer acessar.

E duas tabelas "normais" de banco relacional, que não têm nada de
especial — são só metadados descritivos:

- **`movies`** (`id`, `title`, `synopsis`) — o catálogo, o que existe.
- **`users`** (`id`, `email`, `display_name`) — quem existe.

O pulo do gato: **nem `movies` nem `users` guardam quem pode acessar o
quê.** Essa informação — o coração da autorização — não é uma coluna
numa tabela relacional. Ela é um grafo de relações, e vive inteiramente
dentro do SpiceDB.

---

## Onde cada dado mora — e por que separado

A POC roda com **um único servidor Postgres**, mas com **duas databases
isoladas dentro dele**, cada uma com seu próprio usuário/senha, sem
permissão de acessar a database da outra:

```mermaid
graph TB
    subgraph PG["Um servidor Postgres"]
        subgraph DBAPP["database: app"]
            T1["users (quem existe)"]
            T2["movies (o que existe)"]
        end
        subgraph DBSD["database: spicedb"]
            T3["relation_tuple (quem se relaciona com o quê)"]
            T4["namespace_config (o schema.zed, versionado)"]
            T5["... outras tabelas internas do SpiceDB"]
        end
    end
    R1["role: app_user"] -->|só consegue entrar aqui| DBAPP
    R2["role: spicedb_user"] -->|só consegue entrar aqui| DBSD
```

Por que separar assim, em vez de um Postgres só com tudo junto?

1. **O SpiceDB é dono das próprias tabelas.** As tabelas dentro da
   database `spicedb` — vimos nomes reais como `relation_tuple`,
   `namespace_config`, `caveat`, `relation_tuple_transaction` rodando
   durante a migration desta POC — são geridas inteiramente pelo binário
   do SpiceDB (`spicedb migrate head`). Ninguém no nosso código Clojure
   faz `SELECT` nelas. É "propriedade privada" do SpiceDB, e mexer nelas
   por fora seria como editar o arquivo de um banco de dados enquanto
   ele está aberto — arriscado e sem necessidade, porque existe uma API
   (gRPC) feita exatamente para isso.
2. **Vazamento de credencial não vira vazamento de autorização.** Se
   algum dia a credencial da aplicação (`app_user`) vazar, quem a pegar
   consegue ler `movies`/`users` — chato, mas não consegue nem tentar
   ler ou escrever o grafo de permissões, porque `app_user` não tem
   `CONNECT` na database `spicedb`. É o princípio de menor privilégio
   aplicado literalmente na fronteira do banco.
3. **É o cenário real que a POC precisa provar.** Numa empresa que já
   tem um Postgres de produção com dados de negócio, a pergunta prática
   é "dá pra colocar o SpiceDB do lado, sem misturar com o que já
   existe?" — e a resposta que esta POC valida é: sim, na mesma
   instância, numa database separada.

---

## O schema: onde a regra de negócio vira grafo

O arquivo `app/resources/schema.zed` é o único lugar onde a *regra* de
quem pode ver o quê é declarada — não em código Clojure, em uma
linguagem declarativa própria do SpiceDB:

```zed
definition movie {
    relation required_plan: plan
    relation tag: content_tag
    relation direct_viewer: user
    permission view = (required_plan->is_member) + (tag->has_access) + direct_viewer
}
```

Ler essa última linha em português: "alguém pode `view` (ver) um filme
se: (a) ele é membro do plano exigido pelo filme, OU (b) ele tem acesso
à tag do filme (por outro caminho, tipo produto avulso), OU (c) ele foi
liberado diretamente para aquele filme." Três caminhos, um só resultado.
Isso é o que se chama de **múltiplos caminhos para a mesma permissão** —
um dos padrões centrais do ReBAC (ver o outro artigo para a teoria).

Importante, porque foi um bug real que apareceu durante a implementação:
a **direção** da relação `inherits` entre planos importa. A intenção é
"quem tem o plano superior automaticamente satisfaz os planos
inferiores" — e isso só funciona se o plano *inferior* apontar `inherits`
para o *superior* (`plan:basic --inherits--> plan:medium`), não o
contrário. Modelar autorização como grafo não elimina a possibilidade de
erro — troca "erro escondido num `if`" por "erro visível e testável
numa tupla de relação", o que já é uma vitória, mas exige entender a
direção da seta.

---

## As peças de código, e o papel de cada uma

```mermaid
graph LR
    subgraph HTTP["Camada HTTP"]
        R[rotas] --> AI[autenticação JWT]
    end
    subgraph DOM["Regra de negócio"]
        MS[movie_service] --> AP[["Protocolo AuthzClient"]]
    end
    subgraph SD["Fala com o SpiceDB"]
        SC[SpiceDBClient - gRPC real]
    end
    subgraph DB["Fala com o Postgres"]
        REPO[movies_repo / users_repo]
    end
    R --> MS
    MS --> REPO
    AP -.implementado por.-> SC
```

- **`domain/authz_client.clj`** — não tem nenhuma linha de código que
  fale gRPC. É só uma "promessa de contrato": quatro funções que
  qualquer implementação de autorização precisa saber fazer
  (`check-permission`, `lookup-resources`, `write-relationships!`,
  `write-schema!`). Por quê isso importa? Porque a regra de negócio
  (`movie_service`) nunca precisa saber que existe gRPC, Protobuf ou
  SpiceDB — ela só conhece esse contrato. Se um dia a empresa decidir
  trocar de motor de autorização, essa é a única parede que muda.
- **`infra/spicedb/client.clj`** — a implementação de verdade desse
  contrato, falando o protocolo gRPC do SpiceDB. É aqui que "ver se
  alice pode assistir ao Grinch" vira, de fato, uma chamada de rede para
  o processo do SpiceDB.
- **`domain/movie_service.clj`** — a regra de negócio propriamente dita.
  Duas funções: `can-view?` (pergunta simples, sim/não) e
  `available-movies` (pergunta mais interessante: "me dê a lista de tudo
  que este usuário pode ver" — o SpiceDB responde com uma lista de ids,
  e o Postgres entra depois só para buscar título/sinopse desses ids).
- **`infra/http/*`** — a camada HTTP (Pedestal) e o interceptor de
  autenticação. Importante: **autenticação (quem é você) e autorização
  (o que você pode fazer) são coisas propositalmente separadas aqui.**
  Um JWT inválido nunca chega a consultar o SpiceDB — é rejeitado antes,
  com `401`. Só depois de saber *quem* pergunta é que se pergunta *o
  quê* essa pessoa pode fazer.

## O fluxo completo de uma pergunta de autorização

```mermaid
sequenceDiagram
    participant Cliente
    participant Auth as Autenticação (JWT)
    participant Regra as movie_service
    participant SpiceDB
    participant Postgres

    Cliente->>Auth: "Sou a alice, posso ver o Grinch?" + token
    Auth->>Auth: valida o token (sem falar com o SpiceDB ainda)
    Auth->>Regra: ok, é a alice mesmo
    Regra->>SpiceDB: CheckPermission(movie:grinch, view, user:alice)
    SpiceDB-->>Regra: sim (ela tem o plano exigido)
    Regra-->>Cliente: {"allowed": true}
```

Note o que **não** acontece nesse fluxo: em nenhum momento o código
Clojure percorre a árvore de planos, verifica heranças ou soma
condições manualmente. Ele faz uma pergunta e recebe uma resposta. Toda
a complexidade da regra "por quê" vive dentro do schema, não no código
que pergunta.

## Como rodar, na prática

```bash
make up                    # sobe tudo (gera .env com secrets de dev automaticamente)
make mint-token            # gera um token de teste pra alice
make mint-token USER_ID=bob

curl http://localhost:3000/movies/grinch/access -H "Authorization: Bearer <token>"
curl http://localhost:3000/available-movies -H "Authorization: Bearer <token>"

make seed PROFILE=medium   # popula com volume (200 usuários, 80 filmes)
make bench PROFILE=medium  # mede latência das checagens de permissão

make reset                 # zera tudo (Postgres do zero)
```

Ver o `README.md` na raiz do repositório para a lista completa de
comandos e cenários de teste.

## O veredito que esta POC ajuda a dar

No fim, a pergunta de negócio não é "o SpiceDB funciona" — é "vale a pena
trocar o jeito atual de fazer autorização por este". Os sinais que esta
POC dá para essa decisão:

- **A favor:** a regra de acesso fica num lugar só (o `schema.zed`), em
  vez de espalhada; mudar uma relação (ex.: dar acesso avulso a um
  filme) é uma chamada de API, não um deploy; a separação
  autenticação/autorização fica explícita na arquitetura, não só na
  cabeça de quem escreveu o código.
- **Custo a considerar:** existe uma peça nova rodando (o próprio
  SpiceDB), uma linguagem nova para aprender (`zed`), e — como o bug da
  direção do `inherits` mostrou — a modelagem em grafo tem seus próprios
  jeitos de errar, só que mais visíveis e testáveis do que um `if`
  perdido.
