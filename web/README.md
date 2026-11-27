# MyFinance for iPhone (web app)

The same finance profile as the Android app, as an installable web app. iOS doesn't let apps read SMS, so on
iPhone the data comes from bank and card statements instead.

- **Statements:** PDF (including password-locked), Excel or CSV. There are two ways in:
  - Upload them in the app. They are read in the browser, on the phone.
  - Keep them in your **private S3 bucket**. A GitHub Actions job reads them and saves what they say as JSON in the same bucket, and the app brings that in with a read-only key.
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

## Statements in S3 (optional)

```
s3://<your-bucket>/statements/...        you put statement files here (any sub-folders)
s3://<your-bucket>/processed/index.json  written by the workflow: one entry per file, with its status
s3://<your-bucket>/processed/files/*.json  written by the workflow: what each statement says
```

The repository is public, so its Actions logs are public too. The workflow prints only counts ("File 3: card statement, 90 lines, totals match"). It never prints file names, amounts or payees, and uploads no artifacts.

### 1. Bucket

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

### 2. A role for GitHub Actions (no stored AWS keys)

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

### 3. A read-only key for the phone

1. Create an IAM user (for example `myfinance-phone`) with this policy and nothing else. The key can only read the processed files: it can't list the bucket, read the statements themselves, or change anything.

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{ "Effect": "Allow", "Action": "s3:GetObject", "Resource": "arn:aws:s3:::BUCKET/processed/*" }]
   }
   ```

2. Make an access key for it (Security credentials › Create access key › "Application running outside AWS").
3. On the iPhone, open **More › Statements from S3** and enter:
   - the bucket name
   - the region (for example `ap-south-1`)
   - the access key ID
   - the secret key

   The key is stored only on that phone and is left out of backups. The app syncs when opened (at most every 6 hours), and from **Upload › Sync now**.

### 4. GitHub settings

Settings › Secrets and variables › Actions:

| Kind | Name | Value |
|---|---|---|
| Variable | `S3_BUCKET` | the bucket name |
| Variable | `AWS_REGION` | for example `ap-south-1` |
| Variable | `AWS_ROLE_ARN` | the role's ARN from step 2 |
| Variable (optional) | `STATEMENTS_PREFIX` | default `statements/` |
| Secret | `PDF_PASSWORDS` | passwords of locked PDFs, **one per line**; each is tried in turn |

The **Process statements** workflow runs every day at 7:00 AM India time, and from Actions › Process statements › Run workflow.
- Only new or changed files are read again. Tick "Read every statement again" to redo all of them.
- Locked files no password opened are listed in the app under More › Statements from S3, after a sync.

## Working on it

```sh
cd web
npm ci
npm run dev        # http://localhost:5173/myfinance/
npm test           # unit tests
npm run build      # into dist/
```

`test/oracle.test.ts` compares the reader with the Android app on real statements. It runs only when `ORACLE_DIR` points at a local folder holding the PDFs and the Android dumps. Keep that folder out of the repository.

The S3 job can be run by hand with AWS CLI credentials:

```sh
S3_BUCKET=<bucket> PDF_PASSWORDS="$(cat passwords.txt)" npm run process
```
