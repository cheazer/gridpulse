# MLflow tracking server and model registry (ADR-0011). The official image has no PostgreSQL driver, so this
# adds psycopg 3: SQLAlchemy 2.1 uses it for postgresql:// URIs, and it's the driver services/forecasting uses.
# Keep the MLflow version in step with mlflow-skinny in services/forecasting.
FROM python:3.12.16-slim

RUN pip install --no-cache-dir mlflow==3.17.2 "psycopg[binary]==3.3.6" \
 && useradd --system --uid 10001 --home-dir /mlflow mlflow \
 && mkdir -p /mlartifacts && chown mlflow /mlartifacts

USER mlflow
EXPOSE 5000

# The backend store URI holds a password, so it comes from the environment (MLFLOW_BACKEND_STORE_URI), never the
# command line. --serve-artifacts proxies uploads, so clients only need HTTP access, not the volume.
CMD ["mlflow", "server", "--host", "0.0.0.0", "--port", "5000", \
     "--artifacts-destination", "/mlartifacts", "--serve-artifacts"]
