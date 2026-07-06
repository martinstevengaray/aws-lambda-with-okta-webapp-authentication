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
2. For machine callers (e.g. `client-curl.sh`): create an **API Services**
   (client-credentials) app integration. Note its client ID/secret.
3. For the browser sign-in flow: create a separate **Web Application** app integration —
   `service`-type apps are not allowed to access the `/authorize` endpoint. Use grant type
   **Authorization Code**, set the sign-in redirect URI to
   `https://<function-url>/callback`, and assign your users/groups to the app. Note its
   client ID/secret.
4. Make sure the scope the Lambda requests (`SCOPES` in `OktaAppLambda.java`) exists as a
   custom scope on the authorization server, and that its access policies allow both apps.
5. The default audience of the `default` server is `api://default`; if yours differs, set
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
gitignored, so account- and org-specific values never land in the repo) with
your raw org values:

```sh
export OKTA_URL_PREFIX="<org>"            # e.g. integrator-1234567
export CLIENT_ID="<client id>"            # API Services app (client-credentials, client-curl.sh)
export CLIENT_SECRET="<client secret>"
export WEB_CLIENT_ID="<client id>"        # Web Application app (browser OIDC flow;
export WEB_CLIENT_SECRET="<client secret>"  # empty/unset deploys with the flow disabled)
export AWS_ACCOUNT_ID="<account id>"      # names the tfstate-<account id> state bucket
```

`deploy.sh` sources this file and derives the Terraform inputs from it, exported
as `TF_VAR_*` env vars (Terraform reads any `TF_VAR_<name>` env var as the input
variable `<name>`).

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
