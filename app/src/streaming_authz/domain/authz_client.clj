(ns streaming-authz.domain.authz-client)

(defprotocol AuthzClient
  (write-schema! [this schema-str]
    "Aplica o schema de autorização no SpiceDB. Idempotente — substitui o
     schema ativo.")
  (write-relationships! [this tuples]
    "Escreve/atualiza relações. tuples é uma coleção de mapas
     {:resource-type _ :resource-id _ :relation _ :subject-type _ :subject-id _
      :caveat {:name _ :context {...}}}. :caveat é opcional — só relações
     do tipo ABAC (ex.: region_locked_viewer) o usam; :context ali é o
     valor gravado JUNTO com a tupla (ex.: {:allowed_regions [\"BR\"]}).
     Sempre usa a operação TOUCH (idempotente).")
  (check-permission [this {:keys [resource-type resource-id permission subject-id context]}]
    "Retorna boolean. O subject é sempre do tipo \"user\" nesta POC (só
     usuários chamam a API). :context é opcional — o atributo \"vivo\",
     avaliado só nesta chamada (ex.: {:user_region \"BR\"}), usado por
     caveats. Sem :context, uma relação com caveat nunca é satisfeita
     (fail-closed). Nunca lança exceção para negação — só para falha de
     infraestrutura (SpiceDB fora do ar, timeout gRPC).")
  (lookup-resources [this {:keys [resource-type permission subject-id]}]
    "Retorna uma sequência de resource-ids acessíveis pelo subject."))
