#!/usr/bin/env bash
#
# Applies the Storage CORS policy so the web build can stream media directly from the bucket.
#
# Web keeps no local media, so every attachment is fetched cross-origin from
# firebasestorage.googleapis.com — without this, playback on mira.joetr.com fails silently.
#
# Usage: scripts/apply-storage-cors.sh <bucket>
set -euo pipefail

BUCKET="${1:-}"
if [[ -z "${BUCKET}" ]]; then
  echo "usage: $0 <bucket>   (e.g. my-project.firebasestorage.app)" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gcloud storage buckets update "gs://${BUCKET}" --cors-file="${ROOT}/firebase/storage-cors.json"
echo "CORS applied to gs://${BUCKET}"
