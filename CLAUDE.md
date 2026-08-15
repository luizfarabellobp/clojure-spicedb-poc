# CLAUDE.md

Este arquivo define as regras obrigatórias deste projeto; a seção final é o único ponto de extensão por regra de negócio.

## 1. Skills obrigatórias

Ao explorar, planejar ou implementar uma tarefa, invoque as skills mandatórias cujo escopo bate com o que está sendo feito **nesse momento** — não a lista inteira a cada resposta. O gatilho de cada skill está descrito na tabela abaixo e no `description` do seu próprio `SKILL.md`; uma pergunta puramente conversacional/explicativa que não gera nem revisa código não exige invocar nenhuma. `secure-coding-baseline` e `supply-chain-hardening` são as duas exceções verdadeiramente "sempre" (tocam todo código e toda dependência, respectivamente). Nunca pule uma skill cujo gatilho bateu por a tarefa parecer simples: a simplicidade aparente não elimina a obrigação — o que muda é que uma tarefa fora do gatilho de uma skill não precisa carregá-la. As 14 skills disponíveis são:

- `secure-coding-baseline` — sempre, qualquer projeto.
- `clean-architecture` — sempre que desenhar novas features ou revisar arquitetura, dependências ou separação de camadas.
- `code-quality-refactoring` — sempre que escrever ou refatorar código, no nível de método/classe (design simples, YAGNI/KISS, code smells e refatoração).
- `efficient-execution` — sempre que dividir uma investigação entre várias perguntas, decidir se retoma um agente já usado, escolher modelo/esforço de uma sub-tarefa, ou considerar orquestração multi-agente (Workflow).
- `nextjs-secure-coding` — projeto usa Next.js.
- `supabase-secure-coding` — projeto usa Supabase.
- `fastapi-secure-coding` — projeto usa FastAPI/SQLAlchemy.
- `oauth-identity-secure-coding` — projeto implementa login/SSO via terceiro/IdP.
- `ai-llm-secure-coding` — projeto integra LLM/agentes.
- `mobile-app-secure-coding` — projeto é mobile (nativo/React Native/Flutter).
- `cloud-infrastructure-hardening` — projeto define infraestrutura cloud.
- `orm-migrations-practices` — projeto usa um ORM/query builder com schema próprio (Prisma, Drizzle, TypeORM, SQLAlchemy, Django ORM, ActiveRecord, etc.).
- `supply-chain-hardening` — sempre, qualquer projeto que declare dependências externas (lock file reprodutível, cooldown de dependência, pin por digest/SHA, checklist antes de adicionar dependência nova).
- `pre-push-security-check` — sempre, antes de qualquer `git push`.

Fora dessas 14, `project-bootstrap-onboarding` (ver `bootstrap_gate` na seção 5) é invocada
automaticamente a partir de um gate desta seção, sem depender de pedido do desenvolvedor. A
ferramenta de SDD (ver `sdd_gate` na seção 5) não é fixada por este template — é escolha do
desenvolvedor entre as opções listadas ali.

Quando duas skills discordarem, prevalece a mais restritiva em segurança/qualidade, desde que não contrarie regra de negócio do projeto (seção 5). Se a aplicabilidade de uma skill, spec, ou regra de negócio a uma tarefa específica for genuinamente ambígua, declare a ambiguidade e a interpretação escolhida em vez de assumir silenciosamente — não é necessário ter certeza para prosseguir, mas é necessário ser explícito sobre a incerteza.

## 2. Especificações da empresa (.specs/)

Antes de implementar autenticação, gestão de segredos, ou sincronização de diretório, é obrigatório consultar `.specs/` (hoje: `jumpcloud-auth`, `aws-secret-manager`, `jumpcloud-sync`). Aplique o contrato descrito quando ele for compatível com a stack e as regras de negócio do projeto. Quando não for compatível, adapte-o sem violar a arquitetura da aplicação, ou ignore-o com justificativa registrada no código/PR. Qualquer especificação futura adicionada em `.specs/` segue automaticamente esta mesma regra, sem necessidade de editar este arquivo.

Antes de declarar uma spec "não compatível", é obrigatório ter lido por inteiro o arquivo de spec correspondente em `.specs/<capability>/specs/` (`spec.md` para `jumpcloud-auth` e `jumpcloud-sync`; `secret-manager.md` para `aws-secret-manager`) — nunca decidir isso só a partir de um `design.md`/`proposal.md` de alto nível. A mesma disciplina de grounding vale aqui e ao citar um ID de requisito ASVS (ver `secure-coding-baseline`): não conclua sobre o conteúdo de um documento de referência sem tê-lo aberto.

## 3. Arquitetura

- Separação de camadas: domínio, aplicação e infraestrutura são camadas distintas e não se misturam.
- Regra de dependência: camadas internas (domínio) não conhecem detalhes de camadas externas (frameworks, banco de dados, APIs).
- Princípios SOLID aplicados de forma pragmática, não dogmática.
- Composição antes de herança.
- Testes são parte da definição de pronto — nenhuma tarefa é considerada concluída sem testes que cubram o comportamento implementado.
- Estrutura de pastas organizada por responsabilidade de negócio, não por tipo técnico (evitar agrupar por "controllers", "models", "utils" quando uma organização por domínio for possível).
- Antes de declarar uma tarefa concluída, rode os testes relevantes e confirme que passam, e confira se alguma skill mandatória aplicável (seção 1) ficou sem seguir — não declare "pronto" com base em inferência, apenas com base em verificação executada. Escale a verificação ao tipo de mudança: mudança em infraestrutura, CI, comentário ou documentação pede validação leve e proporcional (sintaxe de YAML/JSON, `shellcheck`/`bash -n`, lint); a suíte de testes completa é reservada para quando código de aplicação de fato muda, rodada uma vez por lote coerente de mudanças relacionadas — não uma vez por arquivo tocado.

<living_docs>
`CLAUDE.md` (seção 5), `SECURITY_GUIDE.md` e `.docs/` são documentação viva, mas a auditoria de
"o que mudou precisa de atualização" não roda a cada Edit/Write nem a cada push — roda em lote, a
cada N commits (padrão: N = 5), controlada por um checkpoint versionado em
`.claude/living-docs-checkpoint` (contém só o SHA do último commit auditado; se o arquivo não
existir, crie-o com o HEAD atual como baseline, sem forçar auditoria nessa primeira vez).

O ponto único de disparo é o gate `pre-push-security-check` (seção 4) — não há checagem
intermediária durante a implementação. A cada `git push`: calcule
`git rev-list --count $(cat .claude/living-docs-checkpoint)..HEAD`; se o resultado for menor que
N, pule a auditoria de documentação (o restante do gate de segurança roda normalmente). Se atingir
ou passar de N, audite se algum componente, integração externa, modelo de
autenticação/autorização ou regra de negócio relevante mudou desde o checkpoint, atualize a seção
correspondente dos três documentos quando necessário, e reescreva o checkpoint local para o HEAD
atual (o arquivo é local/`.gitignore`, não é commitado — cada clone/máquina tem sua própria
contagem). Documentação desatualizada é dívida técnica, não um detalhe cosmético —
mas o custo de auditar isso a cada operação era maior que o problema que resolve; por isso a
verificação é em lote, não contínua.
</living_docs>

## 4. Segurança

Nunca commitar segredos ou credenciais; validar toda entrada externa; seguir as diretrizes do OWASP Top 10; aplicar least privilege por padrão; manter dependências travadas e auditadas. A skill `secure-coding-baseline` é a fonte detalhada e obrigatória dessas regras — consulte-a para orientação específica de implementação.

<production_destructive_op_safeguard>
Toda operação potencialmente destrutiva ou irreversível (DROP, TRUNCATE, DELETE sem filtro, reset de schema/banco, rotação/revogação de credencial, exclusão de recurso de infraestrutura) deve identificar explicitamente qual ambiente é o alvo antes de executar — nunca assumir. Contra produção especificamente: nunca execute a operação sem uma salvaguarda (confirmação explícita do humano, backup/snapshot imediatamente anterior, ou a operação sendo parte de um fluxo já revisado como uma migration com padrão expand-contract) — mesmo que a operação pareça segura ou tenha sido pedida diretamente.
</production_destructive_op_safeguard>

<pre_push_security_gate>
Antes de qualquer `git push`, é obrigatório rodar a checagem rápida da skill
`pre-push-security-check` — que reaplica as skills de segurança já mandatórias para este
projeto (seção 1) e as specs aplicáveis (seção 2) contra o que está sendo empurrado, e executa a
auditoria em lote de documentação viva descrita em `living_docs` quando o checkpoint de commits
indicar que é a vez. Escale a profundidade da reaplicação de segurança ao tamanho e tipo do
diff, mesmo critério de proporcionalidade já usado na seção 3 para testes: diff só de
documentação/comentário/CI pede checagem leve (lint/sintaxe), a reaplicação completa das skills
mandatórias é para diff que toca código de aplicação. Achados Critical ou Important bloqueiam o
push até serem corrigidos ou até confirmação humana explícita aceitando o risco; achados Minor
são apenas reportados.
</pre_push_security_gate>

## 5. Contexto do Projeto / Regras de Negócio

<language_and_tone>
Responda sempre em português (pt-BR), em tom formal e técnico, independente de configuração
local do usuário (ex.: `~/.claude/CLAUDE.md` pessoal, output style da máquina) ou do idioma
usado pelo dev na mensagem. Esta regra existe porque essas configurações são locais por
máquina/usuário e não versionadas — sem uma regra explícita aqui, o tom varia de dev para dev.
Comentários e nomes de identificadores em código seguem a convenção já usada no arquivo/módulo
sendo editado; esta regra vale para a comunicação com o dev (respostas, mensagens de commit,
descrições de PR), não para o conteúdo do código em si.
Termos técnicos consagrados em inglês (commit, merge, deploy, build, push, pull request, branch,
rollback, cache, etc.) permanecem em inglês, sem tradução forçada — e sem conjugação em
português (ex.: "fazer commit"/"dar push", nunca "commitar"/"pushar"/"deployar").
</language_and_tone>

<bootstrap_gate>
Antes da primeira implementação de código (Edit/Write) em um projeto derivado deste template,
verifique se `.claude/project-bootstrap.md` existe:

- **Não existe** → invoque a skill `project-bootstrap-onboarding` antes de escrever qualquer
  código. Ela confirma a stack (autodetectada por manifesto, ou perguntada quando não
  detectável), a estratégia de banco de dados de desenvolvimento, a estratégia de deploy
  (Vercel/Kubernetes/ambos/ainda não decidido), e a ferramenta de SDD escolhida (ver `sdd_gate`
  abaixo), e persiste as quatro respostas nesse arquivo.
- **Já existe** → leia como contexto; não repita as perguntas. Só refaça o fluxo se o
  desenvolvedor pedir explicitamente para atualizar o onboarding (ex.: mudou a estratégia de
  deploy no meio do projeto).

Este gate não dispara em pergunta puramente conversacional/explicativa nem em correção pontual
— mesmo critério de "feature não-trivial" usado pelo `sdd_gate` abaixo. Ele é informativo, não
um bloqueio duro: uma resposta "ainda não decidido" na pergunta de deploy é válida e não impede
a implementação de prosseguir. A pergunta sobre banco de dados apenas registra a intenção do
desenvolvedor — a checagem de segurança em si (nunca produção, identificar o ambiente-alvo)
continua exclusivamente em `production_db_policy` (mais abaixo nesta seção), sem duplicação.

`.claude/project-bootstrap.md` é local (`.gitignore`), não compartilhado via git — cada
clone/máquina responde essas quatro perguntas de novo. É uma opção deliberada, coerente com a
ferramenta de SDD (também local, ver `sdd_gate`): nada aqui impede que dois devs do mesmo
projeto respondam diferente (ex.: ferramentas de SDD distintas) sem conflito de merge.
</bootstrap_gate>

<sdd_gate>
Toda feature não-trivial (não uma correção pontual) é desenvolvida via SDD (Spec-Driven
Development) — algum artefato equivalente a proposta + design + lista de tarefas existe e foi
lido antes de qualquer código de implementação. Este template não fixa qual ferramenta produz
esse artefato; a escolha é do desenvolvedor (registrada em `.claude/project-bootstrap.md`, ver
`bootstrap_gate`, para não repetir a decisão a cada feature — mas nada impede trocar de
ferramenta no meio do projeto, ou devs diferentes usarem ferramentas diferentes). O que é
obrigatório é usar alguma, não uma específica:

- **OpenSpec** — já vendorizado neste template: skills `openspec-propose`, `openspec-explore`,
  `openspec-apply-change`, `openspec-archive-change`, `openspec-sync-specs` em
  `.claude/skills/`. Requer `npx openspec@latest init --tools claude` uma vez por projeto antes
  do primeiro `openspec new change`. Os antigos comandos `/opsx:*` foram removidos deste
  template — invoque as skills diretamente pelo nome.
- **GitHub Spec-Kit** (CLI `specify`) — fluxo `/specify` → `/plan` → `/tasks` → `/implement`,
  workspace em `.specify/`.
- **Fluxo requirements/design/tasks no estilo Kiro** (AWS) — três arquivos por feature
  (`requirements.md`, `design.md`, `tasks.md`); pode ser seguido como convenção de arquivos,
  sem depender da IDE.
- **Superpowers** (plugin pessoal, ver `tdd_gate` abaixo) — skills
  `superpowers:brainstorming` → `superpowers:writing-plans` → `superpowers:executing-plans`.
- **BMAD-METHOD** — framework open-source de planejamento ágil orientado a agentes (agentes de
  papel — analyst/PM/architect — gerando PRD e arquitetura antes da implementação).
- **Task Master** — quebra um PRD em tasks rastreáveis com dependências entre si.

Nunca fabrique à mão um artefato imitando o formato de uma dessas ferramentas como atalho para
pular o fluxo real — se a ferramenta escolhida tem CLI/skill própria, use-a de verdade; não é
uma alternativa válida escrever um `proposal.md`/`design.md`/`requirements.md` que só imita a
forma.

O workspace da ferramenta escolhida (`openspec/`, `.specify/`, `docs/superpowers/`, ou
equivalente) é planejamento local — já coberto pelo `.gitignore` deste template — e não
substitui a documentação final versionada do projeto (`SECURITY_GUIDE.md`/`.docs/`, ver
`living_docs`, e o próprio código com seus testes). Não trate a promoção de uma spec para o
workspace da ferramenta como "arquivamento definitivo": o que precisa sobreviver ao fim da
feature vai para a documentação viva, não para um diretório que nem é versionado.

`.specs/` (seção 2) continua exclusivo para os contratos normativos da empresa, independente da
ferramenta de SDD escolhida — não misture os dois.

Se a aplicabilidade do SDD a uma mudança específica for ambígua (ex.: uma correção pequena que
parece não justificar uma proposta), aplique o mesmo princípio da seção 1: declare a ambiguidade
e a interpretação escolhida em vez de pular a etapa silenciosamente.
</sdd_gate>

<tdd_gate>
Este projeto é uma POC de viabilidade técnica (avaliar se o SpiceDB deve ou não ser adotado em
produção), não um sistema que vai para produção. Por decisão explícita do desenvolvedor em
2026-08-14, TDD, testes automatizados (unitários, de integração ou qualquer outra camada) e
"testes como definição de pronto" (seção 3) **não se aplicam a este repositório**. A entrega é a
API funcional e a documentação/análise gerada pela POC — não há suíte de testes a manter. Se este
projeto evoluir para produção no futuro, esta exceção deve ser revisitada e o piso padrão do
template (TDD obrigatório) volta a valer.
</tdd_gate>

<pii_ban>
Nunca usar PII (dados pessoais reais de usuários, clientes ou colaboradores) em testes, fixtures, seeds, dados de exemplo ou qualquer ambiente que não seja produção. Use sempre dados sintéticos/fake (ex: faker) — nomes, e-mails, CPFs, telefones e endereços gerados artificialmente, nunca extraídos ou anonimizados a partir de dados reais.
</pii_ban>

<production_db_policy>
Independente do banco de dados usado pelo projeto (Supabase, Postgres, MySQL, MongoDB ou
qualquer outro), todo trabalho de implementação e validação (desenvolvimento, testes manuais,
seed, migrations, RLS, queries ad-hoc) roda sempre contra uma instância pessoal ou de
homologação, criada especificamente para essa finalidade — nunca contra o banco de produção,
mesmo quando o desenvolvedor possui credenciais de produção válidas e as fornece ou pede
explicitamente uma operação contra ele. Se essa instância pessoal/homolog ainda não existir, pare
e peça ao desenvolvedor para criá-la antes de prosseguir com qualquer comando contra o banco —
nunca assuma que uma conexão já configurada no `.env` do repositório é segura para uso só porque
está acessível.

Esta é uma proibição mais estrita que a salvaguarda geral de operação destrutiva em produção já
descrita na seção 4 (que cobre qualquer recurso, não só banco de dados, e permite a operação com
salvaguarda humana/backup): para banco de dados especificamente, nenhuma operação — destrutiva ou
não, leitura incluída — é executada contra produção a partir de uma sessão de coding assistant
neste projeto. Se o desenvolvedor precisar mesmo agir em produção, isso acontece fora da sessão
(dashboard, pipeline de deploy já revisado, acesso direto do desenvolvedor), nunca como comando
ad-hoc pedido ao assistente. Ao propor ou executar qualquer comando contra um banco de dados,
identifique explicitamente para o desenvolvedor qual ambiente é o alvo antes de agir. Quando o
banco for Supabase, ver `supabase-secure-coding`, seção "Proteção do ambiente de produção", para
detalhe operacional adicional (identificação de projeto/`ref`, workflow de CLI por ambiente).
</production_db_policy>
