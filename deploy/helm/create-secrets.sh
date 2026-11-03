#!/usr/bin/env bash
# Create (or update) the Secret the GridPulse charts read, from the passwords in .env.
#   bash deploy/helm/create-secrets.sh [namespace]     (default: the current kubectl namespace)
# Passwords never pass through Helm, so they're never in a values file, a rendered manifest or Helm's release history.
# Run it again after changing .env; the next `helm upgrade` re-runs db-users, which applies the new passwords.
set -euo pipefail

env_file=${ENV_FILE:-.env}
[[ -f $env_file ]] || { echo "no $env_file: copy .env.example to .env and set the passwords" >&2; exit 1; }
set -a; source "$env_file"; set +a

# The GridPulse chart reads the database ones; the gridpulse-dev chart reads POSTGRES_*, MLflow's and Grafana's.
keys=(POSTGRES_USER POSTGRES_PASSWORD FORECAST_DB_PASSWORD FORECAST_JOB_DB_PASSWORD MLFLOW_DB_PASSWORD
      SCHEDULER_DB_PASSWORD GRAFANA_ADMIN_PASSWORD)
args=()
for key in "${keys[@]}"; do
  [[ -n ${!key:-} ]] || { echo "$key is not set in $env_file" >&2; exit 1; }
  args+=("--from-literal=$key=${!key}")
done

namespace=()
[[ $# -ge 1 ]] && namespace=(--namespace "$1")

# create --dry-run | apply: creates the Secret the first time and updates it after, without ever deleting it.
kubectl create secret generic gridpulse-secrets "${namespace[@]}" "${args[@]}" --dry-run=client -o yaml \
  | kubectl apply "${namespace[@]}" -f -
