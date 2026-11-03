# Kafka's own init image: creates the topics (create-topics.sh, with rpk) and registers the event schemas
# (register_schemas.py), both idempotent. Build context is the repo root:
#   docker build -f deploy/kafka-init/Dockerfile .
# Compose's redpanda-init and schema-init run it, and so does the Kubernetes init Job, so both platforms create the
# same topics and register the same schemas. Like the db image, the schemas ship with the release that uses them.
FROM docker.redpanda.com/redpandadata/redpanda:v26.2.4 AS redpanda

FROM python:3.12.16-slim
# rpk is one static binary: no need for the whole broker image.
COPY --from=redpanda /opt/redpanda/libexec/rpk /usr/local/bin/rpk
COPY --chmod=0755 deploy/kafka-init/create-topics.sh /usr/local/bin/create-topics.sh
COPY schemas/events/v1/ /opt/gridpulse/schemas/events/v1/
COPY schemas/tools/register_schemas.py /opt/gridpulse/schemas/tools/register_schemas.py

RUN groupadd --system --gid 10001 kafka-init \
 && useradd --system --uid 10001 --gid 10001 --no-create-home kafka-init
USER 10001
# rpk looks for its config under $HOME; with a read-only root filesystem only /tmp is writable.
ENV HOME=/tmp
CMD ["create-topics.sh"]
