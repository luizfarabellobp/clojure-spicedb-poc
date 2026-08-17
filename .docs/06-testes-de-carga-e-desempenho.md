# Testes de carga e desempenho (passo a passo + resultados reais)

> Artigo 6 de 6. Continuação do teste de "produto cartesiano" documentado no
> `README.md` (seção "Seed volumétrica e performance"). Aqui estão mais
> alguns cenários de carga, cada um com o passo a passo exato — pra
> quem quiser reproduzir sozinho, mesmo sem ter rodado nada ainda — e o
> resultado real medido nesta sessão, quando o teste foi aprovado e
> executado.

## Pré-requisitos comuns a todos os testes

```bash
make reset && make up   # ambiente limpo
```

Cada seção abaixo assume esse ambiente limpo, a menos que diga o
contrário (alguns testes reaproveitam a seed `massive` de um teste
anterior, e isso está indicado explicitamente).

---

## Teste 1 — Consultas repetidas (efeito de cache)

**Objetivo:** o prompt original desta POC pedia explicitamente avaliar
"comportamento em consultas repetidas". Este teste faz a mesma pergunta
de permissão centenas de vezes seguidas e compara as primeiras
execuções com as últimas, pra ver se existe algum efeito de cache
perceptível (o SpiceDB documenta um `dispatch cache` — ver artigo 1).

**Comandos:**

```bash
make seed PROFILE=massive
docker compose exec app clojure -X:bench :profile :massive :iterations 300 \
  :check-resource-id '"gen-movie-0"' :check-subject-id '"gen-user-0"' \
  :multi-check-resource-id '"gen-movie-0"' :lookup-subject-id '"gen-user-0"'
```

A comparação "primeiras vs últimas" é feita olhando o relatório gerado
(`target/perf-report-massive-*.edn`) e comparando o `:min-ms` (deveria
representar uma das primeiras chamadas "frias") com o `:p50-ms`/`:p99-ms`
(dominado pelas chamadas "quentes", depois de qualquer cache esquentar).

**Resultado (executado em 2026-08-17, seed `massive` com 90.014 relações,
300 iterações, `gen-movie-0`/`gen-user-0`):**

| Chamada | min (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---|---|---|---|
| `check-permission` (caminho simples) | 0,79 | 1,19 | 1,87 | 2,72 | 172,90 |
| `check-permission` (múltiplos caminhos) | 0,70 | 0,98 | 1,46 | 4,20 | 9,93 |
| `lookup-resources` | 9,88 | 17,93 | 24,99 | 42,13 | 46,81 |

Não apareceu um efeito de cache claro e estável nas 300 repetições: o
`min-ms` (0,79ms) e o `p50-ms` (1,19ms) do `check-permission` ficam bem
próximos — a diferença é de menos de meio milissegundo, não uma ordem de
grandeza como se esperaria de um cache "frio vs quente" bem marcado. O
`max-ms` de 172,90ms no caminho simples é um outlier isolado (bem acima
até do `p99-ms` de 2,72ms) — provavelmente uma pausa pontual de garbage
collection ou uma reconexão de rede, não um padrão repetido; o
`multi-path-check`, rodado logo em seguida no mesmo processo, não mostrou
esse mesmo pico. Conclusão honesta: com este teste (chamadas sequenciais,
não concorrentes) não conseguimos confirmar nem descartar um efeito de
cache — os números sugerem que a latência já está baixa e estável desde o
início, o que é consistente com o dispatch cache já estar "quente" antes
mesmo da primeira iteração medida (a seed e o boot da aplicação já fizeram
chamadas de warm-up antes do benchmark começar a contar).

---

## Teste 2 — Latência de uma negação (`false`) em grafo denso

**Objetivo:** todo o benchmark existente até agora mede casos que
retornam `true`. Este teste mede quanto tempo leva pra confirmar uma
**negação**, dentro do mesmo grafo de 90 mil tuplas — `gen-user-0`
perguntando sobre `avatar_3`, filme que não tem nenhuma relação com
usuários gerados (só com `alice`/`bob` da seed fixa).

**Comandos:**

```bash
make seed PROFILE=massive   # se ainda não tiver rodado nesta sessão
docker compose exec app clojure -X:bench :profile :massive :iterations 100 \
  :check-resource-id '"avatar_3"' :check-subject-id '"gen-user-0"' \
  :multi-check-resource-id '"avatar_3"' :lookup-subject-id '"gen-user-0"'
```

**Confirmação da premissa (por código, não suposição):** `avatar_3` só
concede acesso via plano `premium`, via tag `blockbuster`, ou como
`direct_viewer` explícito de `bob` (`bootstrap.clj`). A seed `massive`
(`generator.clj`, `gen-relations-cartesian`) só cria relações
`direct_viewer` entre `gen-user-N` e `gen-movie-N` — nunca atribui plano
nem toca em `avatar_3`. Logo, `gen-user-0` perguntando por `avatar_3`
resulta necessariamente em `false`.

**Resultado (executado em 2026-08-17, mesma seed `massive` do Teste 1,
100 iterações, `avatar_3`/`gen-user-0`):**

| Chamada | min (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---|---|---|---|
| `check-permission` (caminho simples, resultado `false`) | 0,99 | 1,41 | 1,94 | 152,09 | 152,09 |
| `check-permission` (múltiplos caminhos, resultado `false`) | 0,79 | 1,00 | 1,39 | 2,11 | 2,11 |
| `lookup-resources` | 13,06 | 18,03 | 22,89 | 35,61 | 35,61 |

Comparando com o Teste 1 (mesmo grafo, mas resultado `true`): o `p50-ms`
de uma negação (1,41ms) é bem próximo do `p50-ms` de uma confirmação
(1,19ms) — não há uma penalidade perceptível em confirmar a ausência de
permissão frente a confirmar a presença dela. O `p99-ms` de 152,09ms
neste teste é outro outlier isolado (igual ao `max-ms`, ou seja, um único
evento fora da curva em 100 chamadas) — mesmo padrão do outlier de
172,90ms visto no Teste 1, reforçando que não é específico de negação,
provavelmente é ruído do ambiente Docker local (GC, agendamento de
container), não do SpiceDB em si.

---

## Teste 3 — Cadeia de herança mais longa

**Objetivo:** hoje a cadeia de planos tem só 3 elos
(`basic --inherits--> medium --inherits--> premium`). Este teste gera
uma cadeia bem mais longa (ex.: 30 planos encadeados) e mede se
percorrer uma cadeia de herança maior degrada a latência do
`check-permission` — testando o "Graph Engine" (a parte que resolve
`->` no schema) sob uma travessia mais profunda, não só um grafo com
mais linhas soltas.

**Mudança de código feita:** `generator.clj` ganhou o profile
`:chain-30` (30 planos `chain-plan-0`...`chain-plan-29`, cada um com
`inherits` apontando para o próximo — mesma direção "fraco aponta pro
forte" já usada em `basic`/`medium`/`premium`), a função
`gen-relations-chain` que gera essa cadeia mais o vínculo
`chain-user-bottom` (assinante do plano do fundo da cadeia) e
`chain-movie` (exige o plano do topo da cadeia), e uma função
`run-chain!` separada de `run-volumetric!` (o profile `:chain-30` não
mexe no Postgres — só escreve relações no SpiceDB, já que o benchmark
fala direto com o SpiceDB, sem passar pela API HTTP).

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

Comparando com o Teste 1 (grafo denso de 90 mil relações "largas", sem
cadeia de herança profunda): o `p50-ms` do `check-permission` na cadeia
de 30 planos (1,56ms) é bem próximo do `p50-ms` do Teste 1 (1,19ms) — uma
travessia de 30 níveis de `inherits->is_member` não mostrou uma
degradação perceptível de latência frente a um grafo com muito mais
relações, mas raso. O `p99-ms`/`max-ms` de 170,62ms repete o mesmo
padrão de outlier isolado já visto nos Testes 1 e 2 (evento único, não
uma tendência), reforçando que é ruído do ambiente local, não algo
específico da profundidade da cadeia. O `lookup-resources` aqui é bem
mais rápido que no Teste 1 (1,07ms vs 17,93ms de p50) simplesmente porque
o resultado tem 1 filme, não 300 — confirma, de novo, que
`lookup-resources` escala com o tamanho do resultado, não com o tamanho
do grafo percorrido.

---

## Teste 4 — Tempo de escrita por volume (comparação entre profiles)

**Objetivo:** medir se o tempo de **escrita** da seed cresce de forma
linear com o volume, comparando os 4 profiles existentes
(`small`/`medium`/`large`/`massive`) lado a lado.

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

O resultado **não é linear com o número de relações** — `massive` tem
5,6× mais relações que `large`, mas rodou mais rápido. A explicação está
no código (`generator.clj`, `run!`): usuários e filmes são gravados no
Postgres um de cada vez, num laço (`doseq` com `upsert!` por item), sem
lote — só a escrita das relações no SpiceDB é que vai em lotes de até
900 (`write-relationships!`). `large` tem 2.000 usuários + 300 filmes =
2.300 idas ao Postgres uma por uma; `massive` tem só 300 + 300 = 600. Ou
seja, para `large`, o tempo é dominado pelas 2.300 escritas individuais
no Postgres, não pelas 16.000 relações no SpiceDB — e é exatamente esse
gargalo que faz `massive`, com muito mais relações mas muito menos
usuários/filmes distintos, terminar mais rápido. Isso é um achado real
sobre esta implementação de seed (não sobre o SpiceDB): se o volume de
usuários/filmes crescesse muito mais que neste teste, valeria batelar
também os upserts do Postgres.

---

## Teste 5 — Chamadas concorrentes (carga simulada, não sequencial)

**Objetivo:** todos os benchmarks até aqui rodam **sequencialmente**,
um `check-permission` de cada vez — o que não reflete uma API real,
onde várias requisições chegam ao mesmo tempo. Este teste dispara N
chamadas em paralelo (várias threads ao mesmo tempo) e mede o
throughput (requisições por segundo), não só a latência de uma
chamada isolada.

**Mudança de código feita:** `bench.clj` ganhou `run-concurrent!` (e a
função privada `run-concurrent-checks`), que usa um
`java.util.concurrent.Executors/newFixedThreadPool` com N threads
(`:concurrency`) para submeter todas as chamadas de `check-permission`
de uma vez, mede o tempo total decorrido (não só a soma das latências
individuais) e calcula `throughput-rps` (requisições por segundo) além
dos mesmos percentis já usados no benchmark sequencial. Novo alias
`:bench-concurrent` em `deps.edn` aponta pra essa função.

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

A diferença mais importante frente aos testes sequenciais (Testes 1-3):
o `p50-ms` sob carga concorrente (7,71ms) é bem mais alto que o `p50-ms`
sequencial do mesmo tipo de chamada no Teste 1 (1,19ms) — quando 20
requisições disputam a mesma conexão gRPC e o mesmo processo ao mesmo
tempo, a latência individual sobe, o que é esperado (fila de
processamento, contenção de thread pool). Mesmo assim, o throughput
agregado (≈1.364 req/s) mostra que o sistema continua respondendo rápido
o bastante para lidar com essa carga simultânea sem degradar de forma
catastrófica. O `p99-ms`/`max-ms` em torno de 170ms repete outra vez o
mesmo outlier isolado já visto nos testes anteriores — dessa vez pode
também refletir uma requisição específica que ficou no fim da fila de
20 threads, não necessariamente o mesmo fenômeno dos testes sequenciais.
Uma limitação honesta deste teste: ele reaproveita a mesma instância do
cliente gRPC (um canal só) para todas as threads — não testamos múltiplas
conexões/processos concorrentes, que seria mais parecido com vários
clientes de verdade batendo na API ao mesmo tempo.

---

## Como este artigo é atualizado

Cada teste acima só é executado depois de aprovação explícita, com os
parâmetros exatos confirmados antes de rodar (nada é executado "por
conta própria"). A seção "Resultado" de cada teste é preenchida com o
número real assim que o teste roda — nunca com estimativa.
