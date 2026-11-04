#!/usr/bin/env bash
# Deploys the band server to Google Cloud Run with a Cloud Storage bucket mounted as its data directory.
#
#   PROJECT=my-gcp-project ./server/deploy/cloudrun.sh
#
# Optional: REGION (europe-west1), SERVICE (setlist-server), BUCKET ($PROJECT-setlist-data),
#           ANDROID_SHA256 (comma-separated SHA-256 fingerprints of the APK signing certificates;
#           `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`),
#           IOS_APP_ID (<TeamID>.at.zentriert.setlist), ROOM_TTL_DAYS (90).
#
# Needs: gcloud (logged in, billing enabled). Run from the repository root.
set -euo pipefail

: "${PROJECT:?set PROJECT to your Google Cloud project id}"
REGION="${REGION:-europe-west1}"
SERVICE="${SERVICE:-setlist-server}"
BUCKET="${BUCKET:-$PROJECT-setlist-data}"
ROOM_TTL_DAYS="${ROOM_TTL_DAYS:-90}"

gcloud config set project "$PROJECT" >/dev/null
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com storage.googleapis.com

# The bucket holds only encrypted blobs; keep it private and in the same region as the service.
if ! gcloud storage buckets describe "gs://$BUCKET" >/dev/null 2>&1; then
  gcloud storage buckets create "gs://$BUCKET" --location="$REGION" --uniform-bucket-level-access --public-access-prevention
fi

ENV_VARS="STORE_DIR=/data,ROOM_TTL_DAYS=$ROOM_TTL_DAYS,ANDROID_PACKAGE=at.zentriert.setlist"
[ -n "${ANDROID_SHA256:-}" ] && ENV_VARS="$ENV_VARS,ANDROID_SHA256=${ANDROID_SHA256// /}"
[ -n "${IOS_APP_ID:-}" ] && ENV_VARS="$ENV_VARS,IOS_APP_ID=$IOS_APP_ID"

# One instance at most: the mounted bucket has no file locking. Scale-to-zero keeps the idle cost at ~0;
# an hour is the longest a WebSocket may stay open, the app reconnects by itself.
gcloud run deploy "$SERVICE" \
  --source . \
  --region "$REGION" \
  --allow-unauthenticated \
  --execution-environment gen2 \
  --min-instances 0 --max-instances 1 \
  --cpu 1 --memory 512Mi \
  --timeout 3600 \
  --session-affinity \
  --add-volume "name=data,type=cloud-storage,bucket=$BUCKET" \
  --add-volume-mount "volume=data,mount-path=/data" \
  --set-env-vars "$ENV_VARS"

URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --format 'value(status.url)')
echo
echo "Server läuft: $URL"
echo "  Gesundheit:   $URL/health"
echo "  Datenschutz:  $URL/privacy"
echo
echo "In der App unter Band → Erweitert eintragen: $URL"
echo "Für App-Links in androidApp: setlist.appLinkHost=${URL#https://} in local.properties oder gradle.properties"
echo "Dauerhaft als Standard: DEFAULT_SERVER in shared/src/commonMain/kotlin/at/zentriert/setlist/Settings.kt"
