#!/usr/bin/env bash
# Publishes the iPhone/web parent app (web/) to Firebase Hosting. Run by the GitHub workflow
# after the functions and database rules deploy, with GOOGLE_APPLICATION_CREDENTIALS set.
#   scripts/deploy-web.sh <project-id> <database-url>
# Registers a Firebase web app and creates the Hosting site the first time.
set -euo pipefail
project="$1"
database_url="$2"
fb() { npx --yes firebase-tools@13.35.1 "$@" --project "$project" --non-interactive; }

# 1. A Firebase web app, for the page's sign-in, database and push settings.
app_id=$(fb apps:list WEB --json | jq -r '[.result[]? | select(.displayName == "TV Guard web")][0].appId // empty')
if [ -z "$app_id" ]; then
  app_id=$(fb apps:list WEB --json | jq -r '.result[0].appId // empty')
fi
if [ -z "$app_id" ]; then
  echo "Registering the TV Guard web app with Firebase"
  app_id=$(fb apps:create WEB "TV Guard web" --json | jq -r '.result.appId')
fi
fb apps:sdkconfig WEB "$app_id" --json |
  jq --arg url "$database_url" '.result.sdkConfig + {databaseURL: $url}' > web/firebase-config.json
jq -e '.apiKey and .appId and .projectId' web/firebase-config.json > /dev/null

# 2. The Hosting site: the project's default one, or create it.
sites=$(fb hosting:sites:list --json)
site=$(printf '%s' "$sites" | jq -r '[.result.sites[]? | select(.type == "DEFAULT_SITE")][0].name // empty')
if [ -z "$site" ]; then
  site=$(printf '%s' "$sites" | jq -r '.result.sites[0].name // empty')
fi
if [ -z "$site" ]; then
  echo "Creating the Hosting site"
  fb hosting:sites:create "$project" --app "$app_id" > /dev/null ||
    fb hosting:sites:create "$project-parents" --app "$app_id" > /dev/null
  site=$(fb hosting:sites:list --json | jq -r '.result.sites[0].name')
fi
site=${site##*/}

# 3. Deploy to that site.
jq --arg site "$site" '.hosting.site = $site' firebase.json > "$RUNNER_TEMP/firebase.json"
cp "$RUNNER_TEMP/firebase.json" firebase.json
fb deploy --only hosting --force
url="https://$site.web.app"
echo "Parent web app: $url"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "### iPhone / web parent app: $url" >> "$GITHUB_STEP_SUMMARY"
fi
