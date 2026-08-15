(ns streaming-authz.domain.authz-client)

(defprotocol AuthzClient
  (write-schema! [this schema-str]
    "Aplica o schema de autorização no SpiceDB. Idempotente — substitui o
     schema ativo.")
  (write-relationships! [this tuples]
    "Escreve/atualiza relações. tuples é uma coleção de mapas
     {:resource-type _ :resource-id _ :relation _ :subject-type _ :subject-id _}.
     Sempre usa a operação TOUCH (idempotente).")
  (check-permission [this {:keys [resource-type resource-id permission subject-id]}]
    "Retorna boolean. O subject é sempre do tipo \"user\" nesta POC (só
     usuários chamam a API). Nunca lança exceção para negação — só para
     falha de infraestrutura (SpiceDB fora do ar, timeout gRPC).")
  (lookup-resources [this {:keys [resource-type permission subject-id]}]
    "Retorna uma sequência de resource-ids acessíveis pelo subject."))
