# AWS Lambda with Okta Webapp Authentication
### Confidential Client OIDC Web Application with PKCE. Browser redirects to Okta login page.  

# Requirements
* AWS account, Okta account  
* java, gradle, terraform, aws cli

# Setup
1) Create an S3 bucket to hold terraform state [create-tfstate-bucket.sh](https://github.com/martinstevengaray/bootstrap-utilities/blob/main/infra/create-tfstate-bucket.sh) if one does not already exist.
2) In Okta admin dashboard create new app using: OIDC Web Application with PKCE, and assign app to user.
3) Create new configuration script at: ./local/deployment-config.sh
```bash
export OKTA_URL_PREFIX="<your okta url prefix>"
export OKTA_WEB_CLIENT_ID="<your okta web client id>"
export OKTA_WEB_CLIENT_SECRET="<your okta web client secret>"
export OKTA_SCOPES="<your okta scopes>"
export TERRAFORM_TFSTATE_S3_BUCKET="<your terraform tfstate s3 bucket>"
export TERRAFORM_TFSTATE_S3_REGION="<your terraform tfstate s3 region>"
export DEPLOYMENT_REGION="<your deployment region>"
export LAMBDA_FUNCTION_NAME="<your lambda function name>"
```
4) Deploy lambda and associated infrastructure with [deploy.sh](deploy.sh) -auto-approve
5) Deploy secrets with [deploy-secrets.sh](deploy-secrets.sh)  
6) In Okta admin dashboard add the `<function_url>/callback` as the callback uri for the web app created in step 2. (function_url can be found in the output of deploy.sh)
7) Open lambda url in browser

### optional setup for api-curl.sh
1) Via okta admin dashboard create new machine to machine application with id+secret.
2) Create new configuration script at: ./local/api-curl-config.sh
```bash
export OKTA_URL_PREFIX="<your okta url prefix>"
export OKTA_API_CLIENT_ID="<your okta api client id>"
export OKTA_API_CLIENT_SECRET="<your okta api client secret>"
export OKTA_SCOPES="<your okta scopes>"
export AWS_LAMBDA_URL="<your aws lambda url>"
```
3) use [api-curl.sh](api-curl.sh) for example access.



