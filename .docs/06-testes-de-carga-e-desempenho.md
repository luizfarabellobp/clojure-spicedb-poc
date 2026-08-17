# Testes de carga e desempenho (passo a passo + resultados reais)

> Artigo 6 de 6. Continuação do teste de "produto cartesiano" documentado no
> `README.md` (seção "Seed volumétrica e performance"). Aqui estão mais
> alguns cenários de carga, cada um com o passo a passo exato — pra
> quem quiser reproduzir sozinho, mesmo sem ter rodado nada ainda — e o
> resultado real medido nesta sessão, quando o teste foi aprovado e
> executado.

## Antes dos testes: o que significam p50, p95, p99, min e max?

Todo teste abaixo mostra uma tabela cheia de números como `p50`, `p95`,
`p99`. Antes de ler os resultados, vale entender o que cada um quer
dizer — sem isso, a tabela vira só uma sopa de letras.

Imagine que você fez a mesma pergunta 300 vezes pro SpiceDB e anotou
quanto tempo cada resposta levou, em milissegundos. Agora pegue essas
300 medidas e coloque em ordem, da mais rápida pra mais lenta — tipo uma
fila. Cada estatística é só "um ponto específico dessa fila":

- **min** — a medida mais rápida de todas. A primeira da fila.
- **p50** (a "mediana") — a medida bem no meio da fila. Metade das 300
  respostas foi mais rápida que isso, a outra metade foi mais lenta. É
  o jeito mais honesto de descrever "quanto tempo isso leva
  normalmente", porque não é afetado por um caso raro de lentidão.
- **p95** — a medida que fica na posição 95% da fila (ou seja, 95% das
  respostas foram iguais ou mais rápidas que isso, e só as 5% mais
  lentas ficaram pra trás). Serve pra responder "e nos piores casos, sem
  contar os casos absurdamente raros?".
- **p99** — mesma ideia do p95, mas olhando só pro 1% mais lento.
  Mostra quase o pior cenário, mas ainda descartando aquele 1 evento
  raríssimo (tipo um travamento pontual) que distorceria a leitura.
- **max** — a medida mais lenta de todas, sem descartar nada. A última
  da fila.

O "p" vem de **percentil**: "p95" significa "percentil 95". Quanto mais
próximos min, p50, p95, p99 e max estiverem uns dos outros, mais
**previsível** é a latência (quase todas as respostas demoram
parecido). Quando o `max` (ou o `p99`) dispara muito longe do `p50`,
isso indica que existem casos raros e pontuais bem mais lentos que o
normal — o que os testes abaixo chamam de **outlier** (um valor fora da
curva).

## Pré-requisitos comuns a todos os testes

```bash
make reset && make up   # ambiente limpo
```

Cada seção abaixo assume esse ambiente limpo, a menos que diga o
contrário (alguns testes reaproveitam a seed `massive` de um teste
anterior, e isso está indicado explicitamente).

---

## Teste 1 — Consultas repetidas (efeito de cache)

**Pergunta que este teste tenta responder:** se eu perguntar a mesma
coisa pro SpiceDB várias vezes seguidas, ele fica mais rápido depois da
primeira vez? O SpiceDB documenta um "dispatch cache" (ver artigo 1)
que guarda respostas recentes — este teste tenta enxergar esse cache
funcionando na prática, repetindo a mesma pergunta de permissão 300
vezes seguidas.

**Comandos:**

```bash
make seed PROFILE=massive
docker compose exec app clojure -X:bench :profile :massive :iterations 300 \
  :check-resource-id '"gen-movie-0"' :check-subject-id '"gen-user-0"' \
  :multi-check-resource-id '"gen-movie-0"' :lookup-subject-id '"gen-user-0"'
```

**Como decidimos se o cache apareceu:** se o cache estivesse claramente
"esquentando", esperaríamos ver o `min-ms` (uma das primeiras respostas,
ainda "fria") bem mais lento que o `p50-ms`/`p99-ms` (dominados pelas
respostas de depois, já "quentes"). Uma diferença pequena entre eles
sugere que não dá pra enxergar esse efeito neste teste.

**Resultado (executado em 2026-08-17, seed `massive` com 90.014 relações,
300 iterações, `gen-movie-0`/`gen-user-0`):**

| Chamada | min (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---|---|---|---|
| `check-permission` (caminho simples) | 0,79 | 1,19 | 1,87 | 2,72 | 172,90 |
| `check-permission` (múltiplos caminhos) | 0,70 | 0,98 | 1,46 | 4,20 | 9,93 |
| `lookup-resources` | 9,88 | 17,93 | 24,99 | 42,13 | 46,81 |

**O que isso quer dizer, em bom português:**

- O `min` (0,79ms) e o `p50` (1,19ms) do `check-permission` estão bem
  próximos — menos de meio milissegundo de diferença. Se o cache
  estivesse "esfriado no começo e quente no final" de um jeito forte,
  essa diferença seria bem maior (uma ordem de grandeza, não frações de
  milissegundo).
- O `max` de 172,90ms é um **outlier**: um único evento bem mais lento
  que todo o resto (até mais lento que o `p99`, que já é "quase o pior
  caso"). Isso sugere uma pausa pontual (por exemplo, o "coletor de
  lixo" do Java rodando, ou uma reconexão de rede) — não um padrão que
  se repete, já que a chamada seguinte (`multi-path-check`), rodada
  logo depois no mesmo processo, não teve esse mesmo pico.
- **Conclusão honesta:** com este teste (perguntas em sequência, uma de
  cada vez) não dá pra confirmar nem descartar um efeito de cache. Uma
  explicação plausível é que o cache já estava "quente" antes mesmo da
  primeira medição — a seed e o boot da aplicação já fazem chamadas de
  aquecimento antes do benchmark começar a contar o tempo.

---

## Teste 2 — Latência de uma negação (`false`) em grafo denso

**Pergunta que este teste tenta responder:** até aqui, todo benchmark
mediu perguntas cuja resposta é "sim, pode" (`true`). Confirmar um "não,
não pode" (`false`) demora mais, menos, ou igual? Este teste faz
`gen-user-0` perguntar sobre `avatar_3`, um filme que ele **não** tem
nenhuma relação — só `alice`/`bob` (da seed fixa) têm.

**Comandos:**

```bash
make seed PROFILE=massive   # se ainda não tiver rodado nesta sessão
docker compose exec app clojure -X:bench :profile :massive :iterations 100 \
  :check-resource-id '"avatar_3"' :check-subject-id '"gen-user-0"' \
  :multi-check-resource-id '"avatar_3"' :lookup-subject-id '"gen-user-0"'
```

**Por que temos certeza de que a resposta vai ser `false` (não é
suposição, é o que o código garante):** `avatar_3` só libera acesso pra
quem tem o plano `premium`, pra quem tem a tag `blockbuster`, ou pro
`bob` diretamente (ver `bootstrap.clj`). A seed `massive`
(`generator.clj`) só cria relações do tipo `direct_viewer` ligando
`gen-user-N` ao `gen-movie-N` correspondente — nunca dá plano a esses
usuários gerados, nem toca em `avatar_3`. Então `gen-user-0` perguntando
sobre `avatar_3` só pode dar `false`.

**Resultado (executado em 2026-08-17, mesma seed `massive` do Teste 1,
100 iterações, `avatar_3`/`gen-user-0`):**

| Chamada | min (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---|---|---|---|
| `check-permission` (caminho simples, resultado `false`) | 0,99 | 1,41 | 1,94 | 152,09 | 152,09 |
| `check-permission` (múltiplos caminhos, resultado `false`) | 0,79 | 1,00 | 1,39 | 2,11 | 2,11 |
| `lookup-resources` | 13,06 | 18,03 | 22,89 | 35,61 | 35,61 |

**O que isso quer dizer:**

- Comparando com o Teste 1 (mesmo grafo, mas resposta `true`): o
  `p50` de uma negação (1,41ms) fica bem perto do `p50` de uma
  confirmação (1,19ms). Ou seja, **dizer "não" não é mais caro que
  dizer "sim"** — não existe uma penalidade perceptível de tempo pra
  confirmar que alguém *não* tem acesso.
- O `p99`/`max` de 152,09ms é outro **outlier isolado** (um único
  evento, não uma tendência repetida) — o mesmo tipo de coisa que já
  apareceu no Teste 1 (lá foi 172,90ms). Como esse tipo de pico aparece
  em testes com resultados diferentes (`true` e `false`), a explicação
  mais provável não é o SpiceDB em si, e sim ruído do ambiente local
  (o Docker rodando na sua máquina, pausas do coletor de lixo do Java,
  etc.).

---

## Teste 3 — Cadeia de herança mais longa

**Pergunta que este teste tenta responder:** hoje, na POC, um plano
"herda" de outro numa cadeia curtinha, de só 3 elos
(`basic --inherits--> medium --inherits--> premium`). Se essa cadeia
fosse bem mais comprida — digamos, 30 planos empilhados um atrás do
outro — o SpiceDB ficaria mais lento pra decidir a permissão? Isso testa
uma coisa diferente do Teste 1: lá o grafo era **largo** (muitas
relações "soltas", mas rasas); aqui o grafo é **fundo** (poucas
relações, mas empilhadas — o SpiceDB precisa "descer" 30 níveis pra
achar a resposta).

**Mudança de código feita (antes de rodar):** `generator.clj` ganhou o
profile `:chain-30` — ele cria 30 planos (`chain-plan-0` até
`chain-plan-29`), cada um apontando `inherits` pro próximo da fila,
igual já acontecia com `basic`/`medium`/`premium`. No fim da cadeia tem
um usuário (`chain-user-bottom`) assinando o primeiro plano, e um filme
(`chain-movie`) exigindo o último plano da cadeia — ou seja, pra esse
usuário ter acesso a esse filme, o SpiceDB precisa atravessar os 30
elos.

**Comandos (executados):**

```bash
docker compose exec app clojure -X:seed :profile :chain-30
docker compose exec app clojure -X:bench :profile :chain-30 :iterations 100 \
  :check-resource-id '"chain-movie"' :check-subject-id '"chain-user-bottom"' \
  :multi-check-resource-id '"chain-movie"' :lookup-subject-id '"chain-user-bottom"'
```

**Resultado (executado em 2026-08-17, ambiente resetado, só a cadeia de
30 planos, 100 iterações):**

| Chamada | min (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---|---|---|---|
| `check-permission` (caminho simples) | 1,08 | 1,56 | 2,22 | 170,62 | 170,62 |
| `check-permission` (múltiplos caminhos) | 0,91 | 1,21 | 1,69 | 2,65 | 2,65 |
| `lookup-resources` (resultado: 1 filme) | 0,84 | 1,07 | 1,71 | 4,58 | 4,58 |

**O que isso quer dizer:**

- O `p50` do `check-permission` nesta cadeia funda de 30 planos
  (1,56ms) é bem parecido com o `p50` do Teste 1 (1,19ms), que tinha um
  grafo muito mais largo (90 mil relações), mas raso. Conclusão:
  **atravessar 30 níveis de herança não deixou a resposta perceptivelmente
  mais lenta** frente a um grafo bem maior, só que raso.
- O pico de 170,62ms no `p99`/`max` é o mesmo tipo de outlier isolado já
  visto nos Testes 1 e 2 — reforça que é ruído do ambiente, não algo
  causado pela profundidade da cadeia.
- O `lookup-resources` aqui foi bem mais rápido (1,07ms de p50) que no
  Teste 1 (17,93ms de p50) — mas isso não tem a ver com a cadeia de
  planos, e sim com o tamanho da resposta: aqui só existe 1 filme pra
  listar (`chain-movie`), enquanto no Teste 1 a resposta trazia 300
  filmes. Isso confirma, de novo, que o `lookup-resources` fica mais
  lento conforme o **resultado** cresce, não conforme o grafo por trás
  fica mais complicado.

---

## Teste 4 — Tempo de escrita por volume (comparação entre profiles)

**Pergunta que este teste tenta responder:** se eu dobrar o volume de
dados da seed, o tempo pra escrever tudo também dobra (crescimento
linear), ou cresce de um jeito diferente? Este teste roda a seed nos 4
tamanhos disponíveis (`small`, `medium`, `large`, `massive`) e cronometra
cada um.

**Comandos:**

```bash
make reset && make up
time docker compose exec app clojure -X:seed :profile :small
make reset && make up
time docker compose exec app clojure -X:seed :profile :medium
make reset && make up
time docker compose exec app clojure -X:seed :profile :large
make reset && make up
time docker compose exec app clojure -X:seed :profile :massive
```

(o `make reset && make up` entre cada um garante que cada medição
começa de um banco vazio, sem efeito cumulativo de uma seed sobre a
outra)

**Resultado (executado em 2026-08-17, tempo total do comando
`clojure -X:seed`, incluindo boot da JVM, conexão com Postgres e
SpiceDB, upsert de usuários/filmes e escrita das relações):**

| Profile | Usuários | Filmes | Relações | Tempo total |
|---|---|---|---|---|
| `small` | 20 | 15 | 60 | 2,16s |
| `medium` | 200 | 80 | 1.000 | 2,79s |
| `large` | 2.000 | 300 | 16.000 | 8,71s |
| `massive` | 300 | 300 | 90.000 | 6,89s |

**O que isso quer dizer — e por que o resultado é surpreendente à primeira
vista:**

Olhando só a coluna "Relações", `massive` tem 5,6× mais relações que
`large`. Se o tempo dependesse só disso, `massive` deveria ser bem mais
lento. Só que aconteceu o contrário: `massive` rodou **mais rápido**
que `large`.

A explicação está em como a seed escreve os dados, não no SpiceDB:

- As relações (a parte que vai pro SpiceDB) são escritas em **lotes**
  de até 900 de uma vez (`write-relationships!`) — rápido, mesmo em
  grande volume.
- Já os usuários e filmes (a parte que vai pro Postgres) são escritos
  **um de cada vez**, num laço simples, sem lote (`doseq` +
  `upsert!`).

Então o que decide o tempo total não é "quantas relações", e sim
"quantos usuários + filmes distintos" — porque cada um desses vira uma
ida separada ao banco:

- `large`: 2.000 usuários + 300 filmes = 2.300 idas ao Postgres, uma
  por uma.
- `massive`: só 300 usuários + 300 filmes = 600 idas ao Postgres.

É por isso que `large` demora mais mesmo tendo menos relações no
SpiceDB: o gargalo dele são as 2.300 escritas individuais no Postgres,
não as 16.000 relações. Este é um achado sobre **esta implementação de
seed**, não sobre o SpiceDB — se um dia o número de usuários/filmes
crescesse muito mais, valeria a pena colocar essas escritas no Postgres
em lote também, do mesmo jeito que já é feito para as relações.

---

## Teste 5 — Chamadas concorrentes (carga simulada, não sequencial)

**Pergunta que este teste tenta responder:** todos os testes acima
fazem uma pergunta de cada vez, esperam a resposta, e só então fazem a
próxima (sequencial). Mas uma API de verdade recebe várias perguntas
**ao mesmo tempo**, de usuários diferentes. Este teste dispara 500
perguntas usando 20 "linhas de execução" (threads) simultâneas, e mede
duas coisas novas: o **throughput** (quantas perguntas por segundo o
sistema consegue responder no total) e como a latência de cada pergunta
individual muda quando várias competem ao mesmo tempo.

**Mudança de código feita (antes de rodar):** `bench.clj` ganhou uma
função nova, `run-concurrent!`, que dispara todas as chamadas de uma vez
(usando um pool de threads do Java) em vez de uma atrás da outra, mede o
tempo total que tudo levou pra terminar, e calcula quantas perguntas por
segundo isso equivale (`throughput-rps`). Um novo atalho,
`:bench-concurrent`, foi adicionado ao `deps.edn` pra chamar essa função.

**Comandos (executados):**

```bash
docker compose exec app clojure -X:seed :profile :massive
docker compose exec app clojure -X:bench-concurrent :profile :massive \
  :concurrency 20 :total-requests 500 \
  :check-resource-id '"gen-movie-0"' :check-subject-id '"gen-user-0"'
```

**Resultado (executado em 2026-08-17, seed `massive`, 500 requisições,
20 threads simultâneas):**

| Métrica | Valor |
|---|---|
| Total de requisições | 500 |
| Concorrência | 20 threads |
| Tempo total decorrido | 0,367s |
| Throughput | ≈ 1.364 requisições/segundo |
| min (ms) | 1,86 |
| p50 (ms) | 7,71 |
| p95 (ms) | 20,19 |
| p99 (ms) | 168,86 |
| max (ms) | 170,45 |

**O que isso quer dizer:**

- Sob carga concorrente, o `p50` (7,71ms) ficou bem mais alto que o
  `p50` do mesmo tipo de pergunta rodando sozinha, uma de cada vez, no
  Teste 1 (1,19ms). Isso é esperado: com 20 perguntas competindo ao
  mesmo tempo pela mesma conexão e pelo mesmo processo, cada uma
  individualmente espera um pouco mais na fila.
- Mesmo com essa espera individual maior, o resultado agregado — o
  **throughput** de ≈1.364 perguntas por segundo — mostra que o sistema
  continua dando conta de um volume alto de perguntas simultâneas sem
  travar ou degradar de forma catastrófica.
- O `p99`/`max` em torno de 170ms é, de novo, o mesmo tipo de outlier
  isolado que já apareceu nos testes anteriores — aqui pode ser também
  a última pergunta a sair da fila das 20 threads, e não necessariamente
  o mesmo fenômeno dos testes sequenciais.
- **Limitação honesta deste teste:** todas as 20 threads compartilham a
  mesma conexão gRPC (um único "canal" de rede) com o SpiceDB. Não
  testamos várias conexões/processos diferentes ao mesmo tempo, que
  seria mais parecido com vários clientes de verdade (por exemplo,
  vários servidores da aplicação) batendo na API ao mesmo tempo.

---

## Como este artigo é atualizado

Cada teste acima só é executado depois de aprovação explícita, com os
parâmetros exatos confirmados antes de rodar (nada é executado "por
conta própria"). A seção "Resultado" de cada teste é preenchida com o
número real assim que o teste roda — nunca com estimativa.
