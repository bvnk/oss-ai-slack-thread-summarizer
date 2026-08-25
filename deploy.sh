#!/usr/bin/env bash
#
# Builds and deploys the slack-ai-assistant stack.
#
# The two Slack secrets are NoEcho CloudFormation parameters, so CloudFormation
# will not give them back on an update — they have to be supplied on every
# deploy. They are never stored in the repo. This script resolves them, in order:
#
#   1. SLACK_SIGNING_SECRET / SLACK_BOT_TOKEN in the environment
#   2. a local .env file (gitignored)
#   3. the environment of the currently-deployed Lambda
#
# (3) is what makes a redeploy of an already-running stack a one-liner: the live
# function's own configuration is the source of truth.
#
# Usage:
#   ./deploy.sh              # build, show the changeset, ask before applying
#   ./deploy.sh --yes        # build and deploy without confirmation
#   ./deploy.sh --skip-build # deploy the existing .aws-sam build
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

STACK_NAME="${STACK_NAME:-slack-ai-assistant}"
AWS_REGION="${AWS_REGION:-eu-west-1}"
CONFIRM_CHANGESET=1
RUN_BUILD=1

for arg in "$@"; do
  case "$arg" in
    --yes|-y)     CONFIRM_CHANGESET=0 ;;
    --skip-build) RUN_BUILD=0 ;;
    -h|--help)    sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "error: unknown argument '$arg'" >&2; exit 1 ;;
  esac
done

for tool in aws sam; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "error: '$tool' is not installed." >&2
    [ "$tool" = "sam" ] && echo "       Install it with: brew install aws-sam-cli" >&2
    exit 1
  }
done

aws sts get-caller-identity >/dev/null 2>&1 || {
  echo "error: no valid AWS credentials. Run your SSO login first." >&2
  exit 1
}

# ---------------------------------------------------------------- secrets ----

# shellcheck disable=SC1091
[ -f "$ROOT/.env" ] && set -a && . "$ROOT/.env" && set +a

# Reads one environment variable off the deployed Lambda.
lambda_env_var() {
  local key="$1" fn
  fn="$(aws cloudformation describe-stack-resource \
          --region "$AWS_REGION" --stack-name "$STACK_NAME" \
          --logical-resource-id SlackBotFunctionNative \
          --query 'StackResourceDetail.PhysicalResourceId' --output text 2>/dev/null)" || return 1
  [ -n "$fn" ] && [ "$fn" != "None" ] || return 1
  aws lambda get-function-configuration \
    --region "$AWS_REGION" --function-name "$fn" \
    --query "Environment.Variables.$key" --output text 2>/dev/null
}

resolve_secret() {
  local name="$1" current="${!1:-}"
  if [ -n "$current" ]; then
    echo "==> $name: taken from the environment" >&2
    printf '%s' "$current"
    return
  fi
  echo "==> $name: reading from the deployed Lambda" >&2
  local value
  value="$(lambda_env_var "$name")" || true
  if [ -z "$value" ] || [ "$value" = "None" ]; then
    echo "error: could not resolve $name." >&2
    echo "       Export it, or put it in $ROOT/.env, then re-run." >&2
    exit 1
  fi
  printf '%s' "$value"
}

SLACK_SIGNING_SECRET="$(resolve_secret SLACK_SIGNING_SECRET)"
SLACK_BOT_TOKEN="$(resolve_secret SLACK_BOT_TOKEN)"

# ------------------------------------------------------------------ build ----

if [ "$RUN_BUILD" -eq 1 ]; then
  echo "==> sam build"
  sam build
else
  echo "==> Skipping build, using the existing .aws-sam artifacts"
fi

# ----------------------------------------------------------------- deploy ----

DEPLOY_ARGS=(
  --stack-name "$STACK_NAME"
  --region "$AWS_REGION"
  --capabilities CAPABILITY_IAM
  --resolve-s3
  --no-fail-on-empty-changeset
  --parameter-overrides
    "SlackSigningSecret=$SLACK_SIGNING_SECRET"
    "SlackBotToken=$SLACK_BOT_TOKEN"
)
[ "$CONFIRM_CHANGESET" -eq 1 ] && DEPLOY_ARGS+=(--confirm-changeset)

echo "==> sam deploy --stack-name $STACK_NAME --region $AWS_REGION"
sam deploy "${DEPLOY_ARGS[@]}"

echo
echo "==> Deployed. Effective model:"
aws lambda get-function-configuration \
  --region "$AWS_REGION" \
  --function-name "$(aws cloudformation describe-stack-resource \
      --region "$AWS_REGION" --stack-name "$STACK_NAME" \
      --logical-resource-id SlackBotFunctionNative \
      --query 'StackResourceDetail.PhysicalResourceId' --output text)" \
  --query 'Environment.Variables.BEDROCK_MODEL_ID' --output text
