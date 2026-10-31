#!/usr/bin/env bash
# Build and deploy Mira Firebase Cloud Functions, Firestore rules/indexes, and Storage rules.
#
# Local:
#   firebase login
#   ./scripts/deploy-firebase-backend.sh
#
# CI (set FIREBASE_TOKEN and FIREBASE_PROJECT_ID):
#   ./scripts/deploy-firebase-backend.sh --smoke-test
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIREBASE_DIR="${ROOT_DIR}/firebase"
FUNCTIONS_DIR="${FIREBASE_DIR}/functions"

PROJECT_ID="${FIREBASE_PROJECT_ID:-}"
REGION="${FIREBASE_FUNCTIONS_REGION:-us-central1}"
SMOKE_TEST=0
DRY_RUN=0

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-firebase-backend.sh [options]

Options:
  --project <id>   Firebase project id (default: FIREBASE_PROJECT_ID or firebase/.firebaserc)
  --region <id>    Functions region for smoke tests (default: us-central1)
  --smoke-test     Curl proxyImage after deploy to verify the backend is live
  --dry-run        Build functions but skip firebase deploy
  -h, --help       Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project)
      PROJECT_ID="${2:?--project requires a value}"
      shift 2
      ;;
    --region)
      REGION="${2:?--region requires a value}"
      shift 2
      ;;
    --smoke-test)
      SMOKE_TEST=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "${PROJECT_ID}" ]]; then
  if [[ -f "${FIREBASE_DIR}/.firebaserc" ]]; then
    PROJECT_ID="$(node -e "
      const fs = require('node:fs');
      const rc = JSON.parse(fs.readFileSync('${FIREBASE_DIR}/.firebaserc', 'utf8'));
      process.stdout.write(rc.projects?.default ?? '');
    ")"
  fi
fi

if [[ -z "${PROJECT_ID}" ]]; then
  echo "error: set FIREBASE_PROJECT_ID or pass --project <id>" >&2
  exit 1
fi

if [[ -z "${FIREBASE_TOKEN:-}" ]] && ! firebase projects:list --project "${PROJECT_ID}" >/dev/null 2>&1; then
  echo "error: not logged in to Firebase. Run 'firebase login' or set FIREBASE_TOKEN." >&2
  exit 1
fi

echo "Building Cloud Functions..."
(
  cd "${FUNCTIONS_DIR}"
  npm ci
  npm test
  npm run build
)

if [[ "${DRY_RUN}" -eq 1 ]]; then
  echo "Dry run: skipping firebase deploy for project ${PROJECT_ID}"
  exit 0
fi

echo "Deploying Firebase backend to project ${PROJECT_ID}..."
(
  cd "${FIREBASE_DIR}"
  firebase deploy \
    --project "${PROJECT_ID}" \
    --only functions,firestore,storage \
    --non-interactive
)

echo "Applying Storage CORS..."
if ! "${ROOT_DIR}/scripts/apply-storage-cors.sh"; then
  echo "warning: Storage CORS update failed. Web image loading needs CORS on the bucket."
  echo "         Run ./scripts/apply-storage-cors.sh manually with gcloud or gsutil."
fi

if [[ "${SMOKE_TEST}" -eq 1 ]]; then
  echo "Smoke testing proxyImage..."
  image_url="https://www.google.com/favicon.ico"
  encoded_url="$(node -e "process.stdout.write(encodeURIComponent(process.argv[1]))" "${image_url}")"
  proxy_url="https://${REGION}-${PROJECT_ID}.cloudfunctions.net/proxyImage?url=${encoded_url}"
  curl --fail --show-error --silent --output /dev/null \
    --max-time 30 \
    --retry 3 \
    --retry-delay 5 \
    "${proxy_url}"
  echo "proxyImage smoke test passed"
fi

echo "Firebase backend deploy complete."
