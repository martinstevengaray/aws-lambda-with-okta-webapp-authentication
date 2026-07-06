variable "app_version" {
  description = "App version from the Gradle build (single source of truth in build.gradle). Pass it through: terraform apply -var \"app_version=$(cd .. && ./gradlew -q printVersion)\""
  type        = string
}

variable "region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "us-west-2"
}

variable "function_name" {
  description = "Name of the Lambda function"
  type        = string
  default     = "okta-app-lambda"
}

variable "okta_issuer" {
  description = "Okta custom authorization server issuer, e.g. https://<org>.okta.com/oauth2/default (org-server tokens are opaque and cannot be verified). No default — set TF_VAR_okta_issuer or terraform.tfvars."
  type        = string
}

variable "okta_audience" {
  description = "Expected aud claim of Okta access tokens"
  type        = string
  default     = "api://default"
}

variable "cors_allow_origins" {
  description = "Origins allowed to call the function URL from a browser"
  type        = list(string)
  default     = ["http://localhost:8080"]
}

variable "okta_client_id" {
  description = "Client ID of the Okta Web Application app used for the browser OIDC flow (empty disables it)"
  type        = string
  default     = ""
}

variable "okta_client_secret" {
  description = "Client secret of the Okta Web Application app used for the browser OIDC flow"
  type        = string
  default     = ""
  sensitive   = true
}
