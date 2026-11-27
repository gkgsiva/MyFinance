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
  - Payees: every payee in one list, to give each a proper name (for example a UPI ID as "Milk – Ravi"). The name is used on all their earlier entries and on new ones from statements, pasted SMS, cloud syncs and family files. Also from an entry: change "Paid to" and tick "Call every payment…".
  - Backup and restore, Recently deleted, import history with undo, duplicates, and reversed & refunded.
- **Privacy:** entries are kept only in the browser's storage on the device (IndexedDB). The Heroku app only hands out the app's code; statements and their contents never go there. With sign-in on (see below), only your family's accounts can open it.

App address: your Heroku app's address, for example **https://myfinance-1234.herokuapp.com/** (see "Host it on Heroku" below).

## Put it on the iPhone

1. Open the address above in **Safari**.
2. Tap the **Share** button, then **Add to Home Screen**, then **Add**.
3. Open MyFinance from the Home Screen icon. It runs full screen and works offline.

Data added in Safari before step 2 stays in Safari, not in the Home Screen app. Start from the Home Screen icon.

Data is kept on this phone only, so use **More › Backup and restore** now and then (save to Files or iCloud Drive).

## Host it on Heroku

The repository's top folder has what Heroku needs: `package.json` (builds `web/` with `npm run heroku-postbuild`), a
`Procfile` (runs `web/server.mjs`, a small static server with no packages) and `.slugignore` (leaves the Android code
out). The app is served at the root of the address (`/`).

1. In the Heroku dashboard, choose **New › Create new app** and pick a name, for example `myfinance-siva`.
2. **Settings › Buildpacks › Add buildpack › nodejs**. The repository also holds the Android (Gradle) project, so set
   the buildpack rather than letting Heroku guess.
3. **Deploy › Deployment method › GitHub**: connect `gkgsiva/MyFinance`, then **Enable Automatic Deploys** from
   `main` and press **Deploy Branch** once.
4. Your address is shown under **Settings › Domains** (for example `https://myfinance-siva-1a2b3c4d5e6f.herokuapp.com`).
   Open it on the iPhone as above.

With the Heroku CLI instead:

```sh
heroku create myfinance-siva --buildpack heroku/nodejs
git push heroku main
```

Any small dyno will do: the server only sends files. Turn on [sign-in](#sign-in-recommended-okta-auth0-keycloak-or-any-openid-connect-provider) so only your family can open it. Plain `http://` is sent on to `https://`, which the app's offline
mode needs.

If you use the cloud statements below, put **this address** in the bucket's CORS rule (`AllowedOrigins` / `origin`),
written as `https://` plus the host name, with no path and no slash at the end.

To publish on GitHub Pages as well (at `https://gkgsiva.github.io/myfinance/`), set the Actions variable
`PAGES_DEPLOY` to `true` and choose Settings › Pages › Source › **GitHub Actions**. Then add that origin to CORS too.

## Sign-in (recommended): Okta, Auth0, Keycloak or any OpenID Connect provider

Without sign-in, anyone who learns the Heroku address can open the app. Sign-in makes the Heroku server ask for a
login first, and only lets in the email addresses you list (your family). It's built into `web/server.mjs`
(`web/auth.mjs`, no packages): the standard OpenID Connect login (authorization code with PKCE), then a signed,
HTTP-only cookie that lasts 30 days.

**What it does and doesn't protect.** It guards the app's address: nobody can load the app from Heroku without
signing in with an allowed account. It can't protect entries already on a phone: they live in that browser's
storage, and the installed app still opens offline from its own copy. So keep a screen lock on the phones. When the
phone is online, the app checks with the server each time it opens and sends you to sign in once the 30 days are up.
The cloud bucket key you enter in the app is separate and still needed.

Any OpenID Connect provider works, because the settings are the same everywhere. Free choices:

| Provider | Free plan | Notes |
| --- | --- | --- |
| **Okta** | Integrator Free Plan (developer.okta.com) | Recommended. The free plan's user limit is far more than a family needs. |
| **Auth0** (by Okta) | Free plan, 25,000 monthly users | Simplest screens; can also offer "Continue with Google". |
| **Keycloak** | Free, open source, but you host it | Needs its own server running all the time (and a database), so it costs more than this app. Only worth it if you already run one. |
| Google, Microsoft Entra ID | Free | Also work. Anyone with an account can reach the login page, and ALLOWED_EMAILS keeps them out. |

### 1. Create the app at the provider

Use your Heroku address (for example `https://myfinance-siva-1a2b3c4d5e6f.herokuapp.com`) in place of `APP` below.

**Okta**

1. Sign up for the free Integrator plan at developer.okta.com, and open the Admin Console.
2. **Applications › Applications › Create App Integration**: **OIDC - OpenID Connect**, then **Web Application**.
3. Name it MyFinance. Grant type: **Authorization Code** only.
   - Sign-in redirect URI: `APP/auth/callback`
   - Sign-out redirect URI: `APP/auth/signed-out`
   - Assignments: **Limit access to selected groups** (make a "Family" group under Directory › Groups), or allow
     everyone in your organisation if only family has accounts there.
4. Save. Copy the **Client ID** and **Client secret**.
5. Add your family under **Directory › People** (they get an email to set a password) and put them in the group.
6. The issuer is `https://YOUR-OKTA-DOMAIN/oauth2/default` (Security › API › Authorization Servers shows it as
   "Issuer URI").

**Auth0**

1. Sign up at auth0.com (free plan) and create a tenant, for example `myfinance-siva`, in the region nearest you.
2. **Applications › Applications › Create Application**: **Regular Web Applications**.
3. On its **Settings** tab:
   - Allowed Callback URLs: `APP/auth/callback`
   - Allowed Logout URLs: `APP/auth/signed-out`
   - Copy the **Domain**, **Client ID** and **Client Secret**.
4. Under **Authentication › Database › Username-Password-Authentication**, switch **Disable Sign Ups** on, so
   strangers can't make accounts. Add family members under **User Management › Users**. (Or turn on Google under
   Authentication › Social and let them use their own Google accounts.)
5. The issuer is `https://YOUR-TENANT.REGION.auth0.com/` (the Domain with `https://` and a `/` at the end).

**Keycloak** (if you already run it)

1. In a realm (for example `family`), **Clients › Create client**: OpenID Connect, client ID `myfinance`.
2. Turn on **Client authentication** and **Standard flow** only.
3. Valid redirect URIs `APP/auth/callback`; Valid post logout redirect URIs `APP/auth/signed-out`; Web origins `APP`.
4. Copy the secret from the **Credentials** tab. Make sure users have an email and that it's marked verified.
5. The issuer is `https://YOUR-KEYCLOAK/realms/family`.

### 2. Turn it on in Heroku

In **Settings › Config Vars** (or with the CLI below), add:

| Var | Value |
| --- | --- |
| `OIDC_ISSUER` | the issuer from step 1 |
| `OIDC_CLIENT_ID` | the client ID |
| `OIDC_CLIENT_SECRET` | the client secret |
| `ALLOWED_EMAILS` | who may sign in, comma separated, for example `siva@example.com,asha@example.com` |
| `SESSION_SECRET` | a long random string; make one with `openssl rand -hex 32` |
| `APP_URL` | your Heroku address, for example `https://myfinance-siva-1a2b3c4d5e6f.herokuapp.com` |
| `SESSION_DAYS` | optional: how long a sign-in lasts, default `30` |

```sh
heroku config:set OIDC_ISSUER=https://dev-123456.okta.com/oauth2/default OIDC_CLIENT_ID=... OIDC_CLIENT_SECRET=... \
  ALLOWED_EMAILS=siva@example.com,asha@example.com SESSION_SECRET=$(openssl rand -hex 32) \
  APP_URL=https://myfinance-siva-1a2b3c4d5e6f.herokuapp.com
```

Heroku restarts the app, and sign-in is on. If `OIDC_ISSUER` is set but any other required var is missing, the app
refuses to start (check **More › View logs**) rather than run unprotected. Remove `OIDC_ISSUER` to turn sign-in off.

To let someone else in, add their email to `ALLOWED_EMAILS` (and create their account at the provider). To shut
everyone out at once, for example after losing a phone, change `SESSION_SECRET`: every sign-in ends.

### 3. On the iPhone

Open the address in Safari, sign in, then **Share › Add to Home Screen**. The Home Screen app keeps its own sign-in,
separate from Safari, so it may ask you to sign in once more the first time it opens. The app's name, icon and
manifest are reachable without sign-in so that iOS can install it; they hold nothing private. **More** shows who is
signed in, with **Sign out**. Signing out doesn't delete entries on the phone (use Clear all data for that).

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

Add this CORS rule (Bucket › Permissions › CORS), so the app may read it from the phone. Replace `YOUR-APP.herokuapp.com` with your app's address:

```json
[
  {
    "AllowedOrigins": ["https://YOUR-APP.herokuapp.com"],
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

Create the bucket with uniform access and public access prevention. Then allow the app's address (replace `YOUR-APP.herokuapp.com`) to read it from the phone (CORS):

```sh
gcloud storage buckets create gs://BUCKET --project=PROJECT --location=asia-south1 \
  --uniform-bucket-level-access --public-access-prevention

cat > cors.json <<'JSON'
[{ "origin": ["https://YOUR-APP.herokuapp.com"], "method": ["GET"],
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
npm run dev        # http://localhost:5173/myfinance/  (BASE=/ npm run dev for the root, as on Heroku)
npm test           # unit tests
npm run build      # into dist/
```

`test/oracle.test.ts` compares the reader with the Android app on real statements. It runs only when `ORACLE_DIR` points at a local folder holding the PDFs and the Android dumps. Keep that folder out of the repository.

The workflow's job can be run by hand when signed in with the AWS CLI or gcloud:

```sh
STORAGE_PROVIDER=aws BUCKET=<bucket> PDF_PASSWORDS="$(cat passwords.txt)" npm run process   # or STORAGE_PROVIDER=gcp
```
