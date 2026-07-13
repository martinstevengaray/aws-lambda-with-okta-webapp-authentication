#!/usr/bin/env bash

#curl script to exercise api bearer token for machine to machine access

#load:
#  OKTA_API_CLIENT_ID
#  OKTA_API_CLIENT_SECRET
#  AWS_LAMBDA_URL
source local/api-curl-config.sh

#retrieve bearer token from okta
TOKEN=$(curl -s "https://$OKTA_URL_PREFIX.okta.com/oauth2/default/v1/token" \
  -u "$OKTA_API_CLIENT_ID:$OKTA_API_CLIENT_SECRET" \
  -d "grant_type=client_credentials&scope=$OKTA_SCOPES" | jq -r .access_token)

#use token to interact with lambda
curl -s "$AWS_LAMBDA_URL/hello?who=world" \
      -H "Authorization: Bearer $TOKEN" \
      -H "content-type: application/json" \
      -d '{"greeting": "hi from curl"}' | jq