$PROJECT = "setlist-513808"
$REGION = "europe-west1"
$BUCKET = "$PROJECT-setlist-data"
$SHA = "F6:13:DA:BF:C6:D8:74:87:80:1C:18:DE:D4:6F:2F:30:57:D3:E4:53:3E:6A:E5:EB:30:77:26:DE:14:60:7D:20"

gcloud config set project $PROJECT
# gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com storage.googleapis.com
# gcloud storage buckets create gs://$BUCKET --location=$REGION --uniform-bucket-level-access --public-access-prevention
gcloud run deploy setlist-server --source . --region $REGION --allow-unauthenticated --execution-environment gen2 --min-instances 0 --max-instances 1 --cpu 1 --memory 512Mi --timeout 3600 --session-affinity --add-volume "name=data,type=cloud-storage,bucket=$BUCKET" --add-volume-mount  "volume=data,mount-path=/data"  --set-env-vars "STORE_DIR=/data,ROOM_TTL_DAYS=90,ANDROID_PACKAGE=at.zentriert.setlist,ANDROID_SHA256=$SHA"

