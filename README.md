# okta-app

A template for an Okta-secured AWS Lambda web app.

The Lambda (`okta-app-lambda`) is exposed through a public Lambda **Function URL** and
echoes back the contents of each request (method, path, headers, query string, body,
source IP) plus the verified JWT claims of the caller. Every request must present a valid
Okta-issued access token; there are two ways to authenticate:

- **API clients** send an Okta access token as a `Bearer` token in the `Authorization`
  header. Tokens are verified locally with Okta's
  [okta-jwt-verifier-java](https://github.com/okta/okta-jwt-verifier-java) — signature
  against the issuer's public keys (JWKS), plus `iss` / `aud` / `exp` checks. No token, or
  an invalid one, gets a `401`.
- **Browsers** (requests without a token) are taken through the OIDC **authorization-code
  flow**: redirect to Okta to sign in, exchange the returned `code` for an access token at
  `/callback`, store that token in an `HttpOnly` session cookie, and redirect back to the
  originally requested URL. On subsequent requests the cookie is the bearer token, so the
  round-trip happens only once per session.

## How a request flows

`OktaAppLambda.handleRequest` tries to read and verify a token; on success it returns the
echo response, otherwise it hands off to the unauthenticated path:

1. **Token present and valid** — from the `Authorization: Bearer` header or the `okta_token`
   cookie → verified locally → `200` with a JSON echo of the request and the token's claims.
2. **No/invalid token, request is for `/callback`** — completes the OIDC flow: validates the
   `state` cookie, exchanges the `code` at `<issuer>/v1/token` (authenticating with the web
   app's client ID + secret), verifies the returned access token, sets the `okta_token`
   cookie, clears the temporary `oauth_state` cookie, and redirects to where the user was
   headed.
3. **No/invalid token, any other path** — starts the OIDC flow: redirects the browser to
   `<issuer>/v1/authorize`, stashing the originally requested path in a short-lived
   `oauth_state` cookie.

Token verification is local and offline per request. The only outbound calls to Okta are
the one-time (cached) JWKS key fetch and, during the browser flow, the code-for-token
exchange.

## Project layout

```
src/main/java/com/example/oktaapp/
  OktaAppLambda.java       Handler entry point: wires config, echoes authenticated requests
  OktaDelegate.java        Token verification + OIDC authorization-code flow
  AwsServicesDelegate.java Reads the web client secret from SSM Parameter Store
  HttpUtils.java           Function-URL response builders, HTML error page, URL encoding
  JsonUtils.java           Jackson helpers (serialize, nested-field lookup)
terraform/                 IAM role, Lambda, public Function URL, SSM parameter, S3 backend
deploy.sh                  Build the zip and apply Terraform
deploy_secrets.sh          Push the web client secret into SSM Parameter Store
client-curl.sh             Fetch a client-credentials token and call the function
local/                     Gitignored; holds your org- and account-specific values
```

Terraform state lives in `s3://tfstate-<account id>/okta-app-lambda/terraform.tfstate`.

## Okta setup (manual, one time)

1. Use a **custom authorization server** (Security → API → Authorization Servers — the
   built-in `default` works). Its issuer looks like `https://<org>.okta.com/oauth2/default`.
   Tokens from the *org* authorization server (`https://<org>.okta.com`) are opaque and
   cannot be verified locally — they are rejected.
2. For **API/machine callers** (e.g. `client-curl.sh`): create an **API Services**
   (client-credentials) app integration. Note its client ID and secret.
3. For the **browser sign-in flow**: create a separate **Web Application** app integration —
   `service`-type apps cannot access `/authorize`. Use the **Authorization Code** grant, set
   the sign-in redirect URI to `https://<function-url>/callback`, and assign your
   users/groups. Note its client ID and secret.
4. Ensure the scope the app requests (`OKTA_SCOPES`) exists as a **custom scope** on the
   authorization server and that its access policies allow both apps.
5. The `default` server's audience is `api://default`; if yours differs, set `okta_audience`
   (Terraform variable) to match.

## Configuration

Create `local/config.sh` (the `local/` directory is gitignored, so org- and
account-specific values never land in the repo):

```sh
export OKTA_URL_PREFIX="<org>"                    # e.g. integrator-1234567
export OKTA_API_CLIENT_ID="<client id>"           # API Services app (client-credentials, client-curl.sh)
export OKTA_API_CLIENT_SECRET="<client secret>"
export OKTA_WEB_CLIENT_ID="<client id>"           # Web Application app (browser OIDC flow; empty disables it)
export OKTA_WEB_CLIENT_SECRET="<client secret>"   # NOT read by Terraform — pushed to SSM
export OKTA_SCOPES="<scope>"                       # custom scope both flows request
export AWS_LAMBDA_URL="<function url>"             # set after first deploy; used by client-curl.sh
export AWS_ACCOUNT_ID="<account id>"               # names the tfstate-<account id> state bucket
```

`deploy.sh` sources this file and derives the Terraform inputs, exporting them as `TF_VAR_*`
env vars (Terraform reads any `TF_VAR_<name>` as the input variable `<name>`):

| Env / TF var                | Source                                   | Purpose                                  |
| --------------------------- | ---------------------------------------- | ---------------------------------------- |
| `TF_VAR_okta_issuer`        | derived from `OKTA_URL_PREFIX`           | `.../oauth2/default` issuer              |
| `TF_VAR_okta_web_client_id` | `OKTA_WEB_CLIENT_ID`                     | Web app client ID (empty disables flow)  |
| `TF_VAR_okta_scopes`        | `OKTA_SCOPES`                            | scopes the browser flow requests         |
| `okta_audience`             | Terraform default `api://default`        | expected `aud` claim (override in tfvars) |

The Lambda itself receives its configuration as environment variables set by Terraform:
`OKTA_ISSUER`, `OKTA_AUDIENCE`, `OKTA_WEB_CLIENT_ID`, `OKTA_SCOPES`, and
`OKTA_WEB_CLIENT_SECRET_SSM_PARAMETER_KEY` (the *name* of the SSM parameter holding the
secret — not the secret itself).

A `terraform/terraform.tfvars` file is an optional alternative — see
`terraform.tfvars.example` — but tfvars values take precedence over `TF_VAR_*` env vars.

## Build

Requires **JDK 21** (the Lambda targets the `java21` runtime).

```sh
./gradlew build          # produces build/distributions/okta-app-lambda-<version>.zip
```

The version is defined once in `build.gradle` and readable by tooling via
`./gradlew -q printVersion`. The zip packages compiled classes at the root and dependencies
under `lib/`.

## Deploy

```sh
./deploy.sh              # extra args are passed to `terraform apply`, e.g. ./deploy.sh -auto-approve
```

`deploy.sh` builds the zip, sources `local/config.sh`, and runs `terraform apply`.
On the first run it initializes the S3 backend with
`terraform init -backend-config="bucket=tfstate-$AWS_ACCOUNT_ID"`; init is skipped once
`terraform/.terraform/` exists (delete that directory to force a re-init after a backend or
provider change). Outputs include `function_url` — set it as `AWS_LAMBDA_URL` in
`local/config.sh` for `client-curl.sh`, and register `<function_url>/callback` as
the Web app's sign-in redirect URI in Okta.

### Web client secret (SSM Parameter Store)

The web app's client secret is deliberately **not** a Terraform variable — anything Terraform
touches is readable in the state file. Instead, Terraform creates the `SecureString`
parameter `/okta-app-lambda/okta-web-client-secret` with a placeholder value and ignores the
value thereafter (`lifecycle.ignore_changes`). After the first deploy — and whenever you
rotate the secret in Okta — push the real value:

```sh
./deploy_secrets.sh
```

The script only writes when the value actually changed, so the parameter's version history
reflects real rotations. It fails if the parameter doesn't exist yet (run `./deploy.sh`
first).

The Lambda reads this parameter **once at cold start**, via the AWS Parameters and Secrets
Lambda Extension (a localhost HTTP endpoint — no AWS SDK in the code), and holds the value
for the life of the execution environment. A rotated secret is therefore picked up the next
time a new execution environment cold-starts (redeploy, or let idle containers recycle), not
mid-container. Until the real value is set, browser sign-in fails at the token exchange;
API-client bearer tokens are unaffected.

## Test

Using the helper (client-credentials token, then a call to the function):

```sh
./client-curl.sh
```

Or manually — fetch a token and call the function:

```sh
TOKEN=$(curl -s "https://<org>.okta.com/oauth2/default/v1/token" \
  -u "<api_client_id>:<api_client_secret>" \
  -d "grant_type=client_credentials&scope=<your_scope>" | jq -r .access_token)

curl -s "$AWS_LAMBDA_URL/some/path?foo=bar" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"hello":"world"}' | jq
```

A valid token returns `200` with a JSON echo of the request plus a `jwtClaims` object. Without
a token (or with an invalid one), an API client gets `401`; a browser (`Accept: text/html`)
is redirected into the Okta sign-in flow when the web app is configured.

## Infrastructure notes

- **Runtime:** `java21`, 512 MB, 30 s timeout; handler
  `com.mgaray.oktaapp.OktaAppLambda::handleRequest`.
- **Function URL:** `authorization_type = NONE` (auth is enforced in code, not by AWS IAM),
  with CORS allowing `authorization` and `content-type` headers. Adjust
  `aws_lambda_cors_allow_origins` (default `http://localhost:8080`) for your front end.
- **IAM:** the execution role has basic Lambda logging plus `ssm:GetParameter` and
  `kms:Decrypt` scoped to the one client-secret parameter and the AWS-managed SSM key.
- **State backend:** S3 bucket `tfstate-<account id>`, key `okta-app-lambda/terraform.tfstate`,
  region `us-west-2` (see `terraform/terraform.tf`).
</content>
</invoke>
