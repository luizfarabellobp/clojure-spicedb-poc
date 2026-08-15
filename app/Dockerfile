FROM clojure:temurin-21-tools-deps-bookworm-slim

WORKDIR /app

COPY deps.edn /app/deps.edn
RUN clojure -P

COPY . /app

EXPOSE 3000

CMD ["clojure", "-M:run"]
