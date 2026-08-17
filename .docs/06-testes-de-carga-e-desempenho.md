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

**Resultado:** _pendente — aguardando aprovação para execução._

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

**Resultado:** _pendente — aguardando aprovação para execução._

---

## Teste 3 — Cadeia de herança mais longa

**Objetivo:** hoje a cadeia de planos tem só 3 elos
(`basic --inherits--> medium --inherits--> premium`). Este teste gera
uma cadeia bem mais longa (ex.: 30 planos encadeados) e mede se
percorrer uma cadeia de herança maior degrada a latência do
`check-permission` — testando o "Graph Engine" (a parte que resolve
`->` no schema) sob uma travessia mais profunda, não só um grafo com
mais linhas soltas.

**Isso exige uma mudança de código antes de rodar** — uma função nova
de geração de cadeia de planos, que ainda não existe no
`generator.clj`. Vou propor o código exato antes de implementar.

**Comandos (depois da mudança de código):**

```bash
# escreve os N planos encadeados + um filme exigindo o plano do topo da cadeia
docker compose exec app clojure -X:seed :profile :chain-30
docker compose exec app clojure -X:bench :profile :chain-30 :iterations 100 \
  :check-resource-id '"chain-movie"' :check-subject-id '"chain-user-bottom"'
```

**Resultado:** _pendente — precisa de aprovação para a mudança de código e para a execução._

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

**Resultado:** _pendente — aguardando aprovação para execução._

---

## Teste 5 — Chamadas concorrentes (carga simulada, não sequencial)

**Objetivo:** todos os benchmarks até aqui rodam **sequencialmente**,
um `check-permission` de cada vez — o que não reflete uma API real,
onde várias requisições chegam ao mesmo tempo. Este teste dispara N
chamadas em paralelo (várias threads ao mesmo tempo) e mede o
throughput (requisições por segundo), não só a latência de uma
chamada isolada.

**Isso também exige código novo** — uma função de benchmark concorrente
que ainda não existe em `bench.clj` (o `bench-check` atual usa
`repeatedly`, sequencial). Vou propor o código exato antes de
implementar.

**Comandos (depois da mudança de código):**

```bash
make seed PROFILE=massive   # se ainda não tiver rodado nesta sessão
docker compose exec app clojure -X:bench-concurrent :profile :massive \
  :concurrency 20 :total-requests 500 \
  :check-resource-id '"gen-movie-0"' :check-subject-id '"gen-user-0"'
```

**Resultado:** _pendente — precisa de aprovação para a mudança de código e para a execução._

---

## Como este artigo é atualizado

Cada teste acima só é executado depois de aprovação explícita, com os
parâmetros exatos confirmados antes de rodar (nada é executado "por
conta própria"). A seção "Resultado" de cada teste é preenchida com o
número real assim que o teste roda — nunca com estimativa.
