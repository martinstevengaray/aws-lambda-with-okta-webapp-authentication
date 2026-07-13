#!/usr/bin/env bash
# Push the Okta web app client secret from local/deployment-config.sh to SSM
# Parameter Store. Run after the first ./deploy.sh (terraform creates the
# parameter shell) and again whenever the secret rotates in Okta.
set -euo pipefail
cd "$(dirname "$0")"

#loads:
#  OKTA_WEB_CLIENT_SECRET
#  DEPLOYMENT_REGION
#  LAMBDA_FUNCTION_NAME
source local/deployment-config.sh

# Must match terraform: /<aws_lambda_function_name>/okta-web-client-secret
PARAM_NAME="/${LAMBDA_FUNCTION_NAME}/okta-web-client-secret"

if [ -z "${OKTA_WEB_CLIENT_SECRET:-}" ]; then
  echo "OKTA_WEB_CLIENT_SECRET is empty — nothing to push (browser flow disabled)."
  exit 0
fi

# Terraform owns the parameter's existence; creating it here instead would make
# the first terraform apply fail with ParameterAlreadyExists.
if ! CURRENT=$(aws ssm get-parameter --name "$PARAM_NAME" --with-decryption \
  --region "$DEPLOYMENT_REGION" \
  --query Parameter.Value --output text 2>/dev/null); then
  echo "Parameter $PARAM_NAME not found — run ./deploy.sh first (terraform creates it)." >&2
  exit 1
fi

# Only write when the value changed, so parameter versions stay meaningful.
if [ "$CURRENT" = "$OKTA_WEB_CLIENT_SECRET" ]; then
  echo "$PARAM_NAME already up to date."
  exit 0
fi

aws ssm put-parameter --name "$PARAM_NAME" --type SecureString --overwrite \
  --region "$DEPLOYMENT_REGION" \
  --value "$OKTA_WEB_CLIENT_SECRET" > /dev/null
echo "$PARAM_NAME updated."
