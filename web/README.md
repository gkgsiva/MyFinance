# MyFinance for iPhone (web app)

The same finance profile as the Android app, as an installable web app. iOS doesn't let apps read SMS, so on
iPhone the data comes from bank and card statements, and from bank messages you paste in.

- **Statements:** PDF (including password-locked), Excel or CSV. There are two ways in:
  - Upload them in the app. They are read in the browser, on the phone.
  - Keep them in your **private bucket on AWS S3 or Google Cloud Storage** (your choice). A GitHub Actions job reads them and saves what they say as JSON in the same bucket, and the app brings that in with a read-only key.
- **Bank messages (SMS):** Upload › Paste bank messages. Copy messages in the Messages app and paste one or many, or open a
  text, PDF or CSV file holding many messages (for example a saved or exported conversation). They are read with the
  Android app's SMS reader. You see each one before importing, with its date: from a date line in the file, from the
  message itself, or today, which you can change. A message for a payment already imported from a statement joins that
  line, and the bank's message and the UPI app's message for one payment are kept once, as on Android.
- **The same reading as Android:** the statement reader, rules, duplicate checks and month logic are ported from the Android code (`core/`). The test below checks that real statements give the same lines and the same entry IDs as on Android, so files merge both ways without doubles.
- **Screens:**
  - Home: this month as a ledger with a running bank balance and "card due so far".
  - Entries: search, filters, edit, bulk categories.
  - Manual entries.
  - Insights: by month, categories with budgets, payees, per person, financial years.
  - Card bills: paid / late / short.
  - Monthly PDF report.
  - Family & Android: the same CSV as the Android app's Share / Export, and merge.
  - Backup and restore, Recently deleted, import history with undo, duplicates, and reversed & refunded.
- **Privacy:** entries are kept only in the browser's storage on the device (IndexedDB). There is no server. Only the app's code is on GitHub Pages; statements and their contents never are.

App address: **https://gkgsiva.github.io/myfinance/**

## Put it on the iPhone

1. Open the address above in **Safari**.
2. Tap the **Share** button, then **Add to Home Screen**, then **Add**.
3. Open MyFinance from the Home Screen icon. It runs full screen and works offline.

Data added in Safari before step 2 stays in Safari, not in the Home Screen app. Start from the Home Screen icon.

Data is kept on this phone only, so use **More › Backup and restore** now and then (save to Files or iCloud Drive).

## One-time GitHub setup

1. **Pages:** go to Settings › Pages › Build and deployment › Source and choose **GitHub Actions**.
   - The **Web app** workflow then publishes `web/` on each push to `main`.
   - Run it once by hand from the Actions tab after merging.

## Statements in the cloud (optional): AWS S3 or Google Cloud Storage

Pick one cloud and follow its steps: **A** for AWS, **B** for Google Cloud. Then do the GitHub settings below them.

The bucket is laid out the same way on both:

```
<bucket>/statements/...           you put statement files here (any sub-folders)
<bucket>/processed/index.json     written by the workflow: one entry per file, with its status
<bucket>/processed/files/*.json   written by the workflow: what each statement says
```

The bucket stays private, and so does everything the workflow writes.

The repository is public, so its Actions logs are public too. The workflow prints only counts ("File 3: card statement, 90 lines, totals match"). It never prints file names, amounts or payees, and uploads no artifacts.

## A. AWS S3

### A1. Bucket

Keep the bucket private: Block Public Access stays on.

Add this CORS rule (Bucket › Permissions › CORS), so the app may read it from the phone:

```json
[
  {
    "AllowedOrigins": ["https://gkgsiva.github.io"],
    "AllowedMethods": ["GET"],
    "AllowedHeaders": ["authorization", "x-amz-date", "x-amz-content-sha256", "x-amz-security-token"],
    "MaxAgeSeconds": 3000
  }
]
```

### A2. A role for GitHub Actions (no stored AWS keys)

1. In IAM › Identity providers, add an **OpenID Connect** provider:
   - Provider URL `https://token.actions.githubusercontent.com`
   - Audience `sts.amazonaws.com`
2. Create a role with this **trust policy**. Replace `ACCOUNT_ID`. It only lets this repository's `main` branch use the role:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Principal": { "Federated": "arn:aws:iam::ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com" },
       "Action": "sts:AssumeRoleWithWebIdentity",
       "Condition": {
         "StringEquals": {
           "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
           "token.actions.githubusercontent.com:sub": "repo:gkgsiva/myfinance:ref:refs/heads/main"
         }
       }
     }]
   }
   ```

3. Give the role this **permissions policy**. Replace `BUCKET`:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       { "Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::BUCKET",
         "Condition": { "StringLike": { "s3:prefix": ["statements/*", "processed/*"] } } },
       { "Effect": "Allow", "Action": "s3:GetObject", "Resource": ["arn:aws:s3:::BUCKET/statements/*", "arn:aws:s3:::BUCKET/processed/*"] },
       { "Effect": "Allow", "Action": "s3:PutObject", "Resource": "arn:aws:s3:::BUCKET/processed/*" }
     ]
   }
   ```

### A3. A read-only key for the phone

1. Create an IAM user (for example `myfinance-phone`) with this policy and nothing else. The key can only read the processed files: it can't list the bucket, read the statements themselves, or change anything.

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{ "Effect": "Allow", "Action": "s3:GetObject", "Resource": "arn:aws:s3:::BUCKET/processed/*" }]
   }
   ```

2. Make an access key for it (Security credentials › Create access key › "Application running outside AWS").
3. On the iPhone, open **More › Statements from cloud**, choose **AWS S3**, and enter:
   - the bucket name
   - the region (for example `ap-south-1`)
   - the access key ID
   - the secret key

   The key is stored only on that phone and is left out of backups. The app syncs when opened (at most every 6 hours), and from **Upload › Sync now**.

## B. Google Cloud Storage

In the commands below, replace:
- `PROJECT` with your project ID,
- `PROJECT_NUMBER` with your project number,
- `BUCKET` with the bucket name.

You can run them in Cloud Shell.

### B1. Bucket

Create the bucket with uniform access and public access prevention. Then allow this site to read it from the phone (CORS):

```sh
gcloud storage buckets create gs://BUCKET --project=PROJECT --location=asia-south1 \
  --uniform-bucket-level-access --public-access-prevention

cat > cors.json <<'JSON'
[{ "origin": ["https://gkgsiva.github.io"], "method": ["GET"],
   "responseHeader": ["Authorization", "x-amz-date", "x-amz-content-sha256", "Content-Type"],
   "maxAgeSeconds": 3000 }]
JSON
gcloud storage buckets update gs://BUCKET --cors-file=cors.json
```

### B2. Sign-in for GitHub Actions (Workload Identity Federation, no stored keys)

Create a service account for the workflow. It may read statements and write `processed/`. Only this repository's `main` branch may use it.

```sh
gcloud iam service-accounts create myfinance-actions --project=PROJECT
gcloud storage buckets add-iam-policy-binding gs://BUCKET \
  --member=serviceAccount:myfinance-actions@PROJECT.iam.gserviceaccount.com --role=roles/storage.objectAdmin

gcloud iam workload-identity-pools create github --project=PROJECT --location=global
gcloud iam workload-identity-pools providers create-oidc myfinance --project=PROJECT --location=global \
  --workload-identity-pool=github --issuer-uri=https://token.actions.githubusercontent.com \
  --attribute-mapping=google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref \
  --attribute-condition="assertion.repository=='gkgsiva/myfinance' && assertion.ref=='refs/heads/main'"

gcloud iam service-accounts add-iam-policy-binding myfinance-actions@PROJECT.iam.gserviceaccount.com --project=PROJECT \
  --role=roles/iam.workloadIdentityUser \
  --member=principalSet://iam.googleapis.com/projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository/gkgsiva/myfinance
```

### B3. A read-only key for the phone

The phone uses an **HMAC key** (Cloud Storage's interoperability key) of a second service account. That account may only read `processed/`: it can't list the bucket, read the statements themselves, or change anything.

```sh
gcloud iam service-accounts create myfinance-phone --project=PROJECT
gcloud storage buckets add-iam-policy-binding gs://BUCKET \
  --member=serviceAccount:myfinance-phone@PROJECT.iam.gserviceaccount.com --role=roles/storage.objectViewer \
  --condition='title=processed-only,expression=resource.name.startsWith("projects/_/buckets/BUCKET/objects/processed/")'
gcloud storage hmac create myfinance-phone@PROJECT.iam.gserviceaccount.com --project=PROJECT
```

The last command prints an access ID (`GOOG…`) and a secret. On the iPhone:
1. Open **More › Statements from cloud** and choose **Google Cloud**.
2. Enter the bucket name, the access ID and the secret.

The key is stored only on that phone and is left out of backups.

## GitHub settings (for either cloud)

Settings › Secrets and variables › Actions:

| Kind | Name | Value |
|---|---|---|
| Variable | `STORAGE_PROVIDER` | `aws` or `gcp` |
| Variable | `BUCKET` | the bucket name |
| Variable (AWS) | `AWS_REGION` | for example `ap-south-1` |
| Variable (AWS) | `AWS_ROLE_ARN` | the role's ARN from A2 |
| Variable (Google Cloud) | `GCP_WIF_PROVIDER` | `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/myfinance` |
| Variable (Google Cloud) | `GCP_SERVICE_ACCOUNT` | `myfinance-actions@PROJECT.iam.gserviceaccount.com` |
| Variable (optional) | `STATEMENTS_PREFIX` | default `statements/` |
| Secret | `PDF_PASSWORDS` | passwords of locked PDFs, **one per line**; each is tried in turn |

The **Process statements** workflow runs every day at 7:00 AM India time, and from Actions › Process statements › Run workflow.
- Only new or changed files are read again. Tick "Read every statement again" to redo all of them.
- Locked files no password opened are listed in the app under More › Statements from cloud, after a sync.

The app syncs when opened (at most every 6 hours), and from **Upload › Sync now**. To move from one cloud to the other, change the workflow variables, then choose the other cloud in the app. Entries already brought in stay, and nothing is counted twice.

## Working on it

```sh
cd web
npm ci
npm run dev        # http://localhost:5173/myfinance/
npm test           # unit tests
npm run build      # into dist/
```

`test/oracle.test.ts` compares the reader with the Android app on real statements. It runs only when `ORACLE_DIR` points at a local folder holding the PDFs and the Android dumps. Keep that folder out of the repository.

The workflow's job can be run by hand when signed in with the AWS CLI or gcloud:

```sh
STORAGE_PROVIDER=aws BUCKET=<bucket> PDF_PASSWORDS="$(cat passwords.txt)" npm run process   # or STORAGE_PROVIDER=gcp
```
