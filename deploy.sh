#!/usr/bin/env bash
# Build the Lambda zip and deploy it. Extra args are passed to `terraform apply`
# (e.g. ./deploy.sh -auto-approve). One-time setup: see README "Deploy".
set -euo pipefail
cd "$(dirname "$0")"

./gradlew build
VERSION=$(./gradlew -q printVersion)

# Sets AWS_ACCOUNT_ID and the TF_VAR_okta_* variables read by terraform.
source local/export_variables.sh

# Skipped once initialized — if the backend or providers change, delete terraform/.terraform to re-init.
if [ ! -d terraform/.terraform ]; then
  terraform -chdir=terraform init -backend-config="bucket=tfstate-${AWS_ACCOUNT_ID}" -input=false
fi

terraform -chdir=terraform apply -var "app_version=$VERSION" "$@"
