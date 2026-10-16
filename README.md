# Premisave Developer Service

Developer accounts (password, Google, Facebook and GitHub sign in, email confirmation and password reset), sandbox and production apps, API keys and secrets, and the public `/v1` entry for the Premisave payment API. It authenticates a merchant's key and forwards the call to the wallet service, which does the payments. See `DEVELOPER_SERVICE_SPLIT.md` for the design and `DEVELOPER_API.md` for every endpoint.

Spring Boot 4.1.1, Java 21, MongoDB. No Spring Security, no Redis, no other service dependencies beyond the wallet.

## Run

```bash
export DEVELOPER_JWT_SECRET=...      # at least 32 characters
export API_KEY_PEPPER=...            # at least 16 characters
export INTERNAL_API_KEY=...          # same value as the wallet's INTERNAL_API_KEY
export WALLET_SERVICE_URL=http://localhost:8084
mvn spring-boot:run
```

All settings are in `.env.example` and `src/main/resources/application.yml`. The service reads a file named `.env` from the folder it is started in (`spring.config.import` in `application.yml`), so copy `.env.example` to `.env` there and fill it in; `.env` holds secrets and must be in `.gitignore`. Real environment variables win over the file. Keep the file in plain `KEY=value` lines with no quotes; the mail settings in it use the dotted property names (`spring.mail.host`) because a file, unlike the environment, does not map `SPRING_MAIL_HOST`. It refuses to start without `DEVELOPER_JWT_SECRET` and `API_KEY_PEPPER`. Without `INTERNAL_API_KEY` it starts, but every call that needs the wallet fails.

**Generating secrets.** `DEVELOPER_JWT_SECRET` (at least 32 characters), `API_KEY_PEPPER` (at least 16) and `DEVELOPER_INTERNAL_API_KEY` should be random. Make each one with:

```bash
openssl rand -base64 32
```

Run it once per secret and paste the output after the `=` in `.env`. Use a different value for each, and do not reuse them across environments. Changing `API_KEY_PEPPER` later invalidates every API key, and changing `DEVELOPER_JWT_SECRET` signs everyone out.

## Tests

```bash
mvn clean test
```

The tests use fakes for the repositories and a local HTTP server standing in for the wallet, so they need no database. This code base was compiled and its tests run only with `javac` against stubs, never with Maven or a real MongoDB, so run this first.

## Layout

| Package | Contents |
|:-|:-|
| `developer` | Sign up and sign in, sign out, account, the business (merchant) link |
| `auth` | Email confirmation and password reset links, the mailer, revoked tokens |
| `oauth` | Sign in with Google, Facebook and GitHub |
| `app` | Apps, narrowing rules, pushing app context to the wallet |
| `key` | API keys, secrets, rotation |
| `proxy` | The public `/v1` entry, the onboarding pass-through, rate limiting |
| `wallet` | Client for the wallet's internal API |
| `admin` | Staff endpoints |
| `security` | Password hashing, JWT, the two interceptors |

## Email and social sign in

Both are optional and off until configured.

**Email.** To send through Gmail set `SPRING_MAIL_HOST=smtp.gmail.com`, `SPRING_MAIL_PORT=587`, `SPRING_MAIL_USERNAME` to the full Gmail address, `SPRING_MAIL_PASSWORD` to a 16 character App Password (Google account, Security, 2 step verification on, then App passwords), `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true` and `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true`, and `DEVELOPER_MAIL_FROM` to the same address (Gmail rewrites the sender to the signed in account anyway). `SPRING_MAIL_HOST` is the server name, never an email address. Gmail allows about 500 messages a day for a personal account, which is enough for development but not for a launch; use a transactional provider (any SMTP service) for production. The emails are HTML files in `src/main/resources/email/`, previewed in `email-previews/`; see `DEVELOPER_API.md` section 1.11. In general the mail server is set through the environment: `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, plus `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true` and `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true` for most providers. Set `DEVELOPER_PORTAL_URL` to where the portal runs (links in emails point there) and `DEVELOPER_MAIL_FROM`. Do not put `spring.mail.host` in `application.yml` with an empty value: any value, even an empty one, switches mail on. With no host, no email is sent, a warning is logged, and every endpoint still answers normally. For local work, `DEVELOPER_MAIL_LOG_LINKS=true` writes the confirmation and reset links to the log; links are credentials, so never use it in production.

**Social sign in.** Register an OAuth app with each provider and set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, `FACEBOOK_CLIENT_ID` and `FACEBOOK_CLIENT_SECRET`, `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET`. A provider is on when it has both. Set `DEVELOPER_OAUTH_REDIRECT_URIS` to the portal callback addresses, comma separated and exact, for example `https://portal.example.com/auth/callback/google,https://portal.example.com/auth/callback/github`. Register the same addresses with each provider. With no addresses listed, social sign in is off for every provider. The provider addresses default to their standard endpoints; Facebook's default pins Graph API `v21.0`, which you should check against Facebook's current version and override with `developer.oauth.facebook.authorize-url`, `token-url` and `user-info-url` if needed.

The flows, responses and error codes are in `DEVELOPER_API.md`, sections 1.6 to 1.10.

## Run two or more instances

The rate limiter counts per instance, so with N instances behind a load balancer the effective limit per key is N times `developer.proxy.rate-limit-per-minute`. The app sync job (every 30 seconds) is safe to run on several instances: the wallet ignores an app version it already holds, and an app is only cleared when its version did not change during the push.

## Indexes

Created at startup (`spring.data.mongodb.auto-index-creation: true`): `developers.email` (unique), `developers.merchantId` (unique, sparse), `apps.developerId`, `apps.premisaveWalletId` (unique, sparse, the one-live-app-per-wallet guarantee) and `apps.contextSyncPending`, and `api_keys.appId`. Also: `email_tokens.tokenHash` (unique), `email_tokens.developerId`, and a TTL index on `email_tokens.expiresAt`; a TTL index on `revoked_tokens.expiresAt` (revoked sign in tokens clean themselves up); `oauth_identities` keyed by `provider:subject` with an index on `developerId`. If your Mongo user cannot create indexes, create these by hand; the wallet guarantee depends on the unique one.