# The database's own image: Flyway with db/migrations baked in, plus psql and create-db-users.sh. Build context is
# the repo root:  docker build -f db/Dockerfile .
# Two one-shot jobs run it, in Compose and as Helm hook Jobs before the apps start:
#   migrate    (the default command) applies the migrations;
#   db-users   (entrypoint create-db-users.sh) creates the service logins with passwords from the environment.
# The schema version ships with the release that needs it, instead of being mounted from a checkout.
FROM flyway/flyway:13.10.0-alpine

USER root
RUN apk add --no-cache bash postgresql-client \
 && adduser -D -H -u 10001 migrate
COPY db/migrations/ /flyway/sql/
COPY --chmod=0755 db/create-db-users.sh /usr/local/bin/create-db-users.sh

# Where the migrations are and which schema they own; the connection (FLYWAY_URL, _USER, _PASSWORD) is the
# platform's to set.
ENV FLYWAY_LOCATIONS=filesystem:/flyway/sql \
    FLYWAY_SCHEMAS=grid \
    FLYWAY_DEFAULT_SCHEMA=grid \
    FLYWAY_CONNECT_RETRIES=10

USER 10001
CMD ["migrate"]
