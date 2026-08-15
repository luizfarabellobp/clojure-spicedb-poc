# SECURITY_GUIDE.md

> Documentação viva (ver `CLAUDE.md` seção 3, bloco `<living_docs>`): não é um preenchimento
> único no início do projeto. A auditoria roda em lote, a cada N commits, disparada pelo gate
> `pre-push-security-check` — atualize a seção correspondente quando o checkpoint indicar que é
> a vez.

## Visão geral do sistema

[Uma ou duas frases: o que o sistema faz, para quem, e por que ele existe.]

## Modelo de autenticação

[Todos os caminhos de login (senha, SSO, magic link, API key), como a sessão é mantida
(cookie/JWT/server-side), e onde a validação de fato acontece no código.]

## Modelo de autorização

[Roles/permissões existentes, onde a checagem é aplicada (middleware, decorator, RLS, camada de
serviço) e quaisquer exceções conhecidas (rotas públicas, bypass administrativo).]

## Gestão de segredos e credenciais

[Onde segredos/API keys vivem (variável de ambiente, secret manager, vault), como são
rotacionados, e o que cada credencial de alto privilégio (service_role, admin key) pode acessar.]

## Proteção de dados e privacidade

[Dados sensíveis/PII manipulados, criptografia em repouso e em trânsito, requisitos de
retenção/LGPD aplicáveis, e onde esses dados podem vazar por engano (logs, respostas de erro).]

## Defesas de borda HTTP

[Mecanismo de CSRF (se houver), política de CORS, CSP e qualquer flag de emergência que
desative essas proteções — e se existe log/alerta quando ela é ativada.]

## Superfícies de risco conhecidas

[Pontos que você já sabe que são sensíveis ou historicamente frágeis: upload de arquivo,
conteúdo gerado por IA, webhooks, etc. — e o que existe de fato (não aspiracionalmente) como
mitigação.]

## Stack e arquitetura

[Linguagem/framework, ORM, onde os dados moram em dev vs. produção, onde é feito o deploy real, e
um mapa dos diretórios/módulos principais — o suficiente para alguém que nunca viu o repo saber
onde procurar auth, rotas e regras de negócio.]

## Integrações externas

[Serviços de terceiros usados (LLM, storage, SSO, pagamento) e o que cada um pode acessar —
sobretudo credenciais de alto privilégio.]