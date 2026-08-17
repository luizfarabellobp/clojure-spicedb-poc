# Alternativas ao SpiceDB (e por que existe mais de uma opção)

> Artigo 5 de 5. O SpiceDB não é a única ferramenta que resolve "quem
> pode fazer o quê". Aqui estão as principais alternativas, o que cada
> uma faz de diferente, e por que esta POC escolheu o SpiceDB mesmo
> assim. Ver o artigo 1 pra entender ReBAC/ABAC antes de ler este.

## Por que tem tanta opção pro mesmo problema

O paper Zanzibar do Google (explicado no artigo 1) inspirou várias
ferramentas parecidas, cada uma com um pouco de diferença: umas seguem
o Zanzibar de perto, outras resolvem autorização de um jeito totalmente
diferente (com uma linguagem de política geral, em vez de um grafo de
relações), e outras são camadas comerciais construídas em cima dessas
ferramentas open-source. Este artigo apresenta as principais, pra você
entender onde o SpiceDB se encaixa nesse mapa.

---

## Da mesma família do SpiceDB (inspiradas no Zanzibar)

### OpenFGA

Nasceu dentro da Okta/Auth0 e foi doado pra CNCF (a mesma fundação que
cuida do Kubernetes) em setembro de 2022. Em novembro de 2025, virou um
projeto **CNCF Incubating** — um selo de maturidade:

> [OpenFGA Becomes a CNCF Incubating Project](https://www.cncf.io/blog/2025/11/11/openfga-becomes-a-cncf-incubating-project/)
> [Página oficial do projeto na CNCF](https://www.cncf.io/projects/openfga/)

O repositório oficial se descreve como "um motor de autorização de alta
performance e flexível, construído para desenvolvedores e inspirado no
Google Zanzibar" — <https://github.com/openfga/openfga>. Licença Apache
2.0. Hoje é mantido por gente da Okta e da Grafana. A versão gerenciada
(paga) se chama **Auth0 FGA**.

É a alternativa mais parecida com o SpiceDB — os dois vêm da mesma
ideia (Zanzibar) e misturam ReBAC com um pouco de ABAC. As diferenças
reais, segundo uma comparação independente (não feita por nenhuma das
duas empresas):

> "SpiceDB é gRPC-first, com consistência forte via ZedTokens e uma
> API de observação de mudanças (Watch API) nativa; o OpenFGA/Auth0 FGA
> é REST-first, gerenciado, com cache de tuplas em memória, sem essa
> Watch API nativa."
> — <https://sph.sh/en/posts/spicedb-vs-auth0-fga/>

(A própria Authzed, dona do SpiceDB, também publica uma comparação —
mas como é a empresa concorrente falando do concorrente, vale ler com
um pé atrás: <https://authzed.com/learn/openfga-alternatives>.)

### Ory Keto

Se descreve como a "primeira implementação open-source" dos princípios
do Zanzibar — <https://github.com/ory/keto>, docs em
<https://www.ory.sh/keto/docs/>. Também guarda relações e responde
perguntas de permissão, com API gRPC e REST. O núcleo é open-source,
mas a empresa Ory vende uma camada extra ("Ory Enterprise License") com
SLA e recursos de multi-tenancy mais avançados — não achamos o nome
exato da licença do núcleo aberto, só a existência dessa camada paga
por cima.

---

## Um jeito diferente de resolver: motor de política geral

### Open Policy Agent (OPA)

O OPA não nasceu pra fazer ReBAC — ele é um motor de **política geral**,
com uma linguagem própria chamada Rego, usado bastante em Kubernetes e
infraestrutura. Virou projeto **CNCF Graduated** (o nível mais maduro,
o mesmo do Kubernetes) em janeiro de 2021 —
<https://www.cncf.io/announcements/2021/02/04/cloud-native-computing-foundation-announces-open-policy-agent-graduation/>,
repositório em <https://github.com/open-policy-agent/opa>.

Não achamos, na documentação oficial do OPA, uma declaração dizendo
"isso não faz ReBAC nativamente". Mas achamos uma pista forte e
concreta: a Permit.io (ver abaixo), pra oferecer ReBAC em cima do OPA,
precisou **construir um plugin próprio em Go** só pra adicionar uma
estrutura de dados em grafo que o OPA não tinha de fábrica —
<https://docs.permit.io/overview/how-does-it-work/>. Ou seja: dá pra
fazer, mas não é o que o OPA já vem pronto pra fazer — modelar relações
tipo "pasta contém documento, documento tem 5 níveis de herança" exige
trabalho extra que o SpiceDB já resolve nativamente.

### AWS Cedar / Amazon Verified Permissions

**Cedar** é a linguagem de política open-source da Amazon, anunciada em
2023, licença Apache 2.0 —
<https://aws.amazon.com/about-aws/whats-new/2023/05/cedar-open-source-language-access-control/>,
documentação em <https://docs.cedarpolicy.com/>. **Amazon Verified
Permissions** é o serviço gerenciado da AWS que usa o Cedar por baixo.

O Cedar mistura RBAC e ABAC na mesma política, e suporta hierarquia
(um "Role" pode ser pai de vários "Users", por exemplo) —
<https://docs.aws.amazon.com/prescriptive-guidance/latest/saas-multitenant-api-access-authorization/cedar.html>.
O que não confirmamos: se essa hierarquia cobre cadeias longas de
relação, do jeito que o SpiceDB faz (`required_plan->is_member`,
atravessando vários tipos de objeto) — a documentação que achamos
mostra hierarquia de grupo/papel, não necessariamente o mesmo alcance.

---

## Um modelo bem diferente: biblioteca embutida na aplicação

### Casbin

O Casbin não é um serviço separado — é uma **biblioteca** que você
importa dentro da sua própria aplicação, disponível em várias
linguagens (Go, Java, Python, Node.js, Rust, PHP, .NET, Ruby, entre
outras) — <https://github.com/apache/casbin>, docs em
<https://v1.casbin.org/>. Ele funciona com um arquivo de "modelo"
próprio que descreve RBAC, ABAC ou controle de acesso simples, e você
decide na hora se a permissão vale ou não.

Desde fevereiro de 2026, o Casbin está em processo de incubação na
**Apache Software Foundation** —
<https://incubator.apache.org/projects/casbin.html>. É bem recente e
ainda em andamento (a wiki oficial do processo mostra que a primeira
tentativa de lançamento teve problema de assinatura/licença e está
sendo refeita).

A diferença mais importante pra decidir entre Casbin e SpiceDB: Casbin
roda **dentro** do seu processo (sem outro serviço pra manter no ar,
mas também sem um lugar central onde todo mundo consulta a mesma
regra); SpiceDB é um **serviço à parte**, que vários sistemas diferentes
podem consultar, todos vendo a mesma verdade.

---

## Camadas comerciais construídas em cima de outras ferramentas

Duas empresas vendem "autorização como serviço" — mas nenhuma das duas
inventou um motor do zero; ambas constroem em cima do OPA.

### Permit.io

Confirmado: construído sobre o **OPA + OPAL** (uma ferramenta própria
da Permit.io pra manter dados/políticas sincronizados em tempo real).
Pra suportar ReBAC (que o OPA não tem de fábrica, como já explicamos),
a Permit.io estendeu o OPA com um plugin próprio em Go, com estrutura
de dados em grafo —
<https://docs.permit.io/overview/how-does-it-work/>,
<https://www.permit.io/blog/introduction-to-opal>. Eles também
publicam uma comparação própria entre motores (vale ler sabendo que é
a empresa comparando a si mesma):
<https://www.permit.io/blog/policy-engine-showdown-opa-vs-openfga-vs-cedar>.

### Aserto / Topaz

Confirmado: o **Topaz** (a parte open-source) roda em cima do motor de
decisão do OPA, com um banco local pra guardar relações, inspirado no
Zanzibar — juntando as duas ideias no mesmo produto:

> "Topaz: an OSS cloud-native authorization solution combining OPA &
> Zanzibar"
> — <https://www.aserto.com/blog/topaz-oss-cloud-native-authorization-combines-opa-zanzibar>

Arquitetura completa em <https://docs.aserto.com/docs/architecture>,
repositório em <https://github.com/aserto-dev/topaz>. O produto
comercial "Aserto" é uma central de controle por cima do Topaz, que
também roda sozinho, sem depender da empresa.

---

## Resumindo numa tabela

| Ferramenta | Modelo nativo | Onde roda | Open-source? | Mantido por |
|---|---|---|---|---|
| **SpiceDB** | ReBAC + Caveats (ABAC) | Serviço separado | Sim (Apache 2.0) | Authzed |
| OpenFGA | ReBAC + ABAC | Serviço separado | Sim (Apache 2.0) | CNCF (Okta, Grafana) |
| Ory Keto | ReBAC (estilo Zanzibar) | Serviço separado | Núcleo aberto + camada paga | Ory |
| OPA | Política geral (ABAC/policy-as-code) | Biblioteca ou serviço | Sim | CNCF (Graduated) |
| AWS Cedar | RBAC + ABAC (hierarquia) | Biblioteca ou serviço gerenciado (AWS) | Sim (Apache 2.0) | AWS |
| Casbin | RBAC/ABAC/ACL | Biblioteca embutida | Sim | Apache (em incubação) |
| Permit.io | ReBAC/ABAC (via OPA + plugin próprio) | Serviço gerenciado | Parcial (motor é OPA) | Permit.io |
| Aserto/Topaz | ReBAC + política (OPA + Zanzibar) | Serviço separado | Sim (Topaz) | Aserto |

---

## Por que esta POC usa o SpiceDB

Em resumo (o artigo 1 explica com mais detalhe): o SpiceDB resolve
nativamente o problema de "múltiplos caminhos pra mesma permissão"
(plano, produto avulso, concessão direta) sem precisar de plugin
extra, e ainda ganhou, com os Caveats, um jeito de misturar ABAC no
mesmo motor quando precisar de atributo calculado na hora — o mesmo
caminho que a Netflix usou. Isso não quer dizer que as outras
ferramentas não resolveriam o problema — o OpenFGA, por exemplo, é uma
alternativa bem próxima, com trade-offs diferentes de consistência e
API. A escolha do SpiceDB nesta POC é sobre o que fez mais sentido
testar primeiro, não uma afirmação de que é a única opção certa.

## O que não confirmamos (registrado por honestidade)

- O identificador exato de licença do núcleo aberto do Ory Keto (só
  confirmamos a existência de uma camada paga por cima).
- Uma declaração oficial do próprio OPA dizendo que ele não cobre ReBAC
  nativamente — o que temos é a evidência indireta do plugin que a
  Permit.io precisou construir.
- Se a hierarquia de entidades do Cedar cobre cadeias de relação tão
  longas quanto as do SpiceDB, ou só hierarquias mais simples de
  grupo/papel.

## Referências

- [OpenFGA — GitHub](https://github.com/openfga/openfga) e [openfga.dev](https://openfga.dev/)
- [OpenFGA Becomes a CNCF Incubating Project](https://www.cncf.io/blog/2025/11/11/openfga-becomes-a-cncf-incubating-project/)
- [SpiceDB vs Auth0 FGA — comparação independente](https://sph.sh/en/posts/spicedb-vs-auth0-fga/)
- [Ory Keto — GitHub](https://github.com/ory/keto) e [docs](https://www.ory.sh/keto/docs/)
- [Open Policy Agent — GitHub](https://github.com/open-policy-agent/opa) e [CNCF Graduation](https://www.cncf.io/announcements/2021/02/04/cloud-native-computing-foundation-announces-open-policy-agent-graduation/)
- [AWS anuncia o Cedar](https://aws.amazon.com/about-aws/whats-new/2023/05/cedar-open-source-language-access-control/) e [docs do Cedar](https://docs.cedarpolicy.com/)
- [Apache Casbin — GitHub](https://github.com/apache/casbin) e [status de incubação na Apache](https://incubator.apache.org/projects/casbin.html)
- [Como o Permit.io funciona (OPA + OPAL)](https://docs.permit.io/overview/how-does-it-work/)
- [Topaz: combinando OPA e Zanzibar](https://www.aserto.com/blog/topaz-oss-cloud-native-authorization-combines-opa-zanzibar) e [arquitetura](https://docs.aserto.com/docs/architecture)
