# Putting HRMS online for free

Free pieces: **Neon** (databases), **Render** (the API and OpenFGA), optional **Brevo/Gmail SMTP** (emails) and **Microsoft Entra** (sign-in with Microsoft).
Free Render services **sleep after ~15 minutes without traffic** and take about a minute to wake up; that is the trade-off of free.

## 0. Before you start
- Put the project on GitHub (private repo is fine). `.env` and `data/` are git-ignored: never commit them.
- Decide the public address of the API (Render gives `https://hrms-api.onrender.com`, or similar) and of your web app.

## 1. Databases (Neon, free)
1. Create a **new Neon project** for production (do not reuse the dev database).
2. In it make two databases: `hrms` and `openfga`.
3. Note for `hrms`: the **pooled** host (`…-pooler…`), the **direct** host, the user and password.
4. For `openfga` copy the connection string: `postgres://USER:PASS@HOST/openfga?sslmode=require`.

## 2. Secrets (run on your machine)
```
openssl rand -base64 32     # AUTH_KEY_ENC  (keep a safe copy)
openssl rand -hex 24        # FGA_API_TOKEN (also used as OPENFGA_AUTHN_PRESHARED_KEYS)
```

## 3. Prepare OpenFGA's database (once)
OpenFGA's image has no shell, so create its tables from your machine:
```
docker run --rm openfga/openfga:v1.22.0 migrate --datastore-engine postgres \
  --datastore-uri 'postgres://USER:PASS@HOST/openfga?sslmode=require'
```

## 4. Create the services on Render
1. Render → **New → Blueprint** → pick the repo (it reads `render.yaml`).
2. Fill the prompts: DB values, `AUTH_KEY_ENC`, `FGA_API_TOKEN`, `OPENFGA_DATASTORE_URI`, `OPENFGA_AUTHN_PRESHARED_KEYS` (same as `FGA_API_TOKEN`),
   `HRMS_BOOTSTRAP_ADMIN_EMAIL`, `APP_BASE_URL`. Leave the rest empty for now.
3. After the first creation set on **hrms-api**: `AUTH_ISSUER` = its own public URL, `FGA_API_URL` = the public URL of **hrms-fga**.

## 5. First start (creates the authorization model)
1. Set `SPRING_PROFILES_ACTIVE` to `prod,fga-setup` on hrms-api and deploy.
2. In the logs find: `OpenFGA ready. To pin it, set in .env:  FGA_STORE_ID=…  FGA_MODEL_ID=…`.
3. Put those two values into the environment variables, set the profile back to `prod`, redeploy.
   (The database tables are created automatically at startup by the migrations.)

## 6. First admin
1. `HRMS_BOOTSTRAP_ADMIN_EMAIL` must be on a domain the organization allows (default `ivoyant.com`). To use another domain, run once in the Neon SQL editor:
   `update org set email_domains = '{yourdomain.com}';`
2. On startup the first admin is created and the log shows: `Account set-up link for the first admin …`. Open that link (or, with SMTP set, use the email),
   choose a password and set up the authenticator app. Save the recovery codes.
3. Signed in as that admin, run **POST /api/v1/admin/fga/sync** once to copy relationships into OpenFGA.
4. Optional: to let everyone sign in with passwords, set the organization's password policy to STANDARD or STRONG; with Microsoft sign-in leave it on ENTRA and fill the `ENTRA_*` values
   (redirect URI to register in Entra: `<AUTH_ISSUER>/api/v1/auth/sso/microsoft/callback`).

## 7. Emails
Set `SMTP_HOST`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM` (Brevo's free plan allows 300 emails a day; Gmail needs an app password). Without them, emails stay in the `notification_email` table and are not sent.

## Good to know on the free plan
- **Sleeping**: the first request after a quiet period is slow. A free uptime pinger (for example UptimeRobot on `/actuator/health`) keeps it awake, within Render's monthly free hours.
  Scheduled jobs (daily reminders, exits, alerts) only run while the app is awake.
- **Uploaded files** (documents, photos, exports) are stored on the server's disk, which is **wiped on every restart** on the free plan. Before real use, move file storage to a bucket
  (Cloudflare R2 free tier) by implementing `FileStore`, or use a paid disk.
- **Front end and API on different sites**: the sign-in cookie is `SameSite=Strict`. Serve the web app and the API under the same site (for example `app.example.com` and `api.example.com`) or the browser will not send the cookie.
- **Never** run the `local` profile in production: it switches on the `X-Dev-User` header login. The app refuses to start if `prod` and `local` are both active.
