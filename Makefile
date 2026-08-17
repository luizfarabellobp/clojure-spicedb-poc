PROFILE ?= small
ITERATIONS ?= 50
USER_ID ?= alice
CONCURRENCY ?= 10
TOTAL_REQUESTS ?= 200

.PHONY: help env db up down reset seed bench bench-concurrent mint-token logs ps

help:
	@echo "Alvos disponíveis:"
	@echo "  make env                 - gera .env com secrets de desenvolvimento (só se não existir)"
	@echo "  make db                  - sobe só postgres + spicedb (sem a app)"
	@echo "  make up                  - sobe a stack inteira (build incluso) e espera a app ficar pronta"
	@echo "  make down                - para os containers (mantém os dados)"
	@echo "  make reset               - para os containers e apaga os volumes (reseta tudo)"
	@echo "  make seed PROFILE=medium - roda a seed volumétrica (small|medium|large|massive|chain-30, default: small)"
	@echo "  make bench PROFILE=medium ITERATIONS=100 - roda o benchmark sequencial de latência"
	@echo "  make bench-concurrent PROFILE=massive CONCURRENCY=20 TOTAL_REQUESTS=500 - roda o benchmark com N threads simultâneas e mede throughput"
	@echo "  make mint-token USER_ID=bob - gera um JWT de teste para o user-id informado"
	@echo "  make logs                - segue os logs da app"
	@echo "  make ps                  - lista os containers da stack"

# Secrets de desenvolvimento/teste, geradas localmente a cada ambiente novo.
# Nunca use estes valores fora de um ambiente local/isolado.
.env:
	@echo "Gerando .env com secrets de desenvolvimento (uso local apenas)..."
	@{ \
		echo "SPICEDB_PRESHARED_KEY=$$(openssl rand -hex 32)"; \
		echo "JWT_HS256_SECRET=$$(openssl rand -hex 32)"; \
	} > .env
	@echo ".env criado."

env: .env

db: env
	docker compose up -d postgres spicedb-migrate spicedb

up: env
	docker compose up --build -d
	@echo "Aguardando a aplicação subir..."
	@until docker compose logs app 2>/dev/null | grep -q "started on port"; do sleep 1; done
	@echo "Aplicação pronta em http://localhost:3000"

down:
	docker compose down

reset:
	docker compose down -v

seed:
	docker compose exec app clojure -X:seed :profile :$(PROFILE)

bench:
	docker compose exec app clojure -X:bench :profile :$(PROFILE) :iterations $(ITERATIONS)

bench-concurrent:
	docker compose exec app clojure -X:bench-concurrent :profile :$(PROFILE) :concurrency $(CONCURRENCY) :total-requests $(TOTAL_REQUESTS)

mint-token:
	docker compose exec app clojure -X:mint-token :user-id '"$(USER_ID)"'

logs:
	docker compose logs -f app

ps:
	docker compose ps
