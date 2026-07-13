# Webapp Template: AWS Lambda with Okta Authentication 
### Confidential client, OIDC Web Application with PKCE, using browser redirects to Okta login page.  

# Requirements
1) AWS account
2) Okta account
3) terraform
4) aws cli
5) java
6) gradle

# Setup:
1) Create an S3 bucket to hold terraform state [create-tfstate-bucket.sh](https://github.com/martinstevengaray/bootstrap-utilities/blob/main/infra/create-tfstate-bucket.sh) if one does not already exist.
2) In Okta admin dashboard create new app using: OIDC Web Application with PKCE, and assign app to user
3) Create new configuration script at: ./local/deployment-config
```bash
export OKTA_URL_PREFIX=""
export OKTA_WEB_CLIENT_ID=""
export OKTA_WEB_CLIENT_SECRET=""
export OKTA_SCOPES=""
export TERRAFORM_TFSTATE_BUCKET=""
```
4) Deploy lambda and associated infrastructure with [deploy.sh](deploy.sh) -auto-approve
5) Deploy secrets with [deploy-secrets.sh](deploy-secrets.sh)  
6) Open lambda url in browser (link can be found in output of deploy.sh)

### optional setup for api-curl.sh
1) Via okta admin dashboard create new machine to machine application with id+secret
2) Create new configuration script at: ./local/api-curl-config.sh
```bash
export OKTA_API_CLIENT_ID=""
export OKTA_API_CLIENT_SECRET=""
export AWS_LAMBDA_URL=""
```
3) use [api-curl.sh](api-curl.sh) for example access



