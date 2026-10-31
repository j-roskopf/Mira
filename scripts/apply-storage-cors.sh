#!/usr/bin/env bash
# Apply CORS rules to the Firebase Storage bucket so the web app can load images
# directly via Coil/Ktor without routing through the proxyImage Cloud Function.
#
# Requires gcloud or gsutil authenticated for the Firebase/GCP project.
#
# Usage:
#   ./scripts/apply-storage-cors.sh
#   FIREBASE_PROJECT_ID=mira-dffbd ./scripts/apply-storage-cors.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIREBASE_DIR="${ROOT_DIR}/firebase"
CORS_FILE="${FIREBASE_DIR}/storage-cors.json"

if [[ ! -f "${CORS_FILE}" ]]; then
  echo "error: missing ${CORS_FILE}" >&2
  exit 1
fi

BUCKET="$(node -e "
  const fs = require('node:fs');
  const config = JSON.parse(fs.readFileSync('${FIREBASE_DIR}/firebase.json', 'utf8'));
  const bucket = config.storage?.bucket;
  if (!bucket) process.exit(1);
  process.stdout.write(bucket);
")"

if [[ -z "${BUCKET}" ]]; then
  echo "error: storage.bucket not set in firebase/firebase.json" >&2
  exit 1
fi

echo "Applying Storage CORS to gs://${BUCKET}..."
if command -v gcloud >/dev/null 2>&1; then
  gcloud storage buckets update "gs://${BUCKET}" --cors-file="${CORS_FILE}"
elif command -v gsutil >/dev/null 2>&1; then
  gsutil cors set "${CORS_FILE}" "gs://${BUCKET}"
else
  echo "error: install gcloud or gsutil to apply Storage CORS" >&2
  exit 1
fi

echo "Storage CORS applied."
