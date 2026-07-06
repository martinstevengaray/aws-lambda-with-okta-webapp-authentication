# okta-app

An AWS Lambda (`okta-app-lambda`) exposed via a public Lambda Function URL that echoes back
request contents (method, path, headers, query params, body, source IP). Every request must
carry a valid Okta-issued bearer token — verified with Okta's
[okta-jwt-verifier-java](https://github.com/okta/okta-jwt-verifier-java) library (JWKS fetch +
signature, `iss`/`aud`/`exp` checks); anything else gets a `401`.

- `src/main/java/com/example/oktaapp/OktaAppLambda.java` — handler + token enforcement
- `terraform/` — IAM role, Lambda, public Function URL; state in `s3://tfstate-<account id>/okta-app-lambda/`

## Okta setup (manual, one time)

1. In the Okta admin console, use a **custom authorization server** (Security → API →
   Authorization Servers — the built-in `default` works). The issuer looks like
   `https://<org>.okta.com/oauth2/default`. Tokens from the *org* authorization server
   (`https://<org>.okta.com`) are opaque and will be rejected.
2. Create an app integration that can obtain access tokens from that server (e.g. an
   **API Services / client-credentials** app for machine callers, or your existing SSO app
   for user flows). Note the client ID/secret.
3. The default audience of the `default` server is `api://default`; if yours differs, set
   `okta_audience` accordingly.

## Build

Requires JDK 21 (the Lambda targets the `java21` runtime).

```sh
./gradlew build          # produces build/distributions/okta-app-lambda-<version>.zip
```

The version is defined once in `build.gradle` and readable by tooling via
`./gradlew -q printVersion`.

## Deploy

One-time setup: create `local/export_variables.sh` (the `local/` directory is
gitignored, so account- and org-specific values never land in the repo). It is
sourced by `deploy.sh`, and Terraform reads any `TF_VAR_<name>` env var as the
input variable `<name>`:

```sh
export OKTA_URL_PREFIX="<org>"                 # e.g. integrator-1234567
export AWS_ACCOUNT_ID="<account id>"           # names the tfstate-<account id> state bucket
export TF_VAR_okta_issuer="https://${OKTA_URL_PREFIX}.okta.com/oauth2/default"
export TF_VAR_okta_client_id="<client id>"     # optional: browser OIDC flow
export TF_VAR_okta_client_secret="<client secret>"
```

(A `terraform/terraform.tfvars` file is an optional alternative — see
`terraform.tfvars.example` — but note tfvars values take precedence over
`TF_VAR_*` env vars.)

On first run `deploy.sh` runs
`terraform init -backend-config="bucket=tfstate-$AWS_ACCOUNT_ID"`, so the state
bucket name is not hardcoded either. Init is skipped once
`terraform/.terraform/` exists; delete that directory to force a re-init (e.g.
after a backend or provider change).

Then build and deploy in one step (extra args are passed to `terraform apply`):

```sh
./deploy.sh
```

Outputs include `function_url`.

## Test

Get a token (client-credentials example):

```sh
TOKEN=$(curl -s https://<org>.okta.com/oauth2/default/v1/token \
  -u "<client_id>:<client_secret>" \
  -d grant_type=client_credentials -d scope=<your_scope> | jq -r .access_token)
```

Call the function:

```sh
curl -s "$FUNCTION_URL/some/path?foo=bar" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"hello":"world"}'
```

Without a token (or with an invalid one) the Lambda returns `401`.
