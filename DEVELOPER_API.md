# Premisave Developer Service API

Three surfaces on one service (default port 8091):

| Surface | Path | Who calls it | Authentication |
|:-|:-|:-|:-|
| Portal API | `/api/v1/**` | The developer portal (a browser app) | `Authorization: Bearer <accessToken>`, except `/api/v1/auth/**` |
| Public API | `/v1/{live\|sandbox}/**` | Merchants' servers | API key and secret |
| Admin API | `/internal/admin/**` | Premisave staff tooling | `X-API-Key: <DEVELOPER_INTERNAL_API_KEY>` |

Every response uses the same envelope:

```json
{ "success": true, "message": "Account retrieved", "data": { }, "timestamp": "2026-10-16T09:30:00.123" }
```

Errors have `success: false` and `data` carries `code`, `requestId` and optionally `details`:

```json
{ "success": false, "message": "Invalid API key or secret", "data": { "code": "INVALID_API_KEY", "requestId": "req_..." }, "timestamp": "..." }
```

Every response carries an `X-Request-Id` header. Quote it when asking for help. List endpoints return `data.content` and `data.page` with `size`, `number`, `totalElements` and `totalPages`, and accept `page` (from 0) and `size`.

The wallet's payment operations behind `/v1` are documented in `COMMERCIAL_API_ENDPOINTS.md`. In that document, a path such as `POST /payments` under the live base is `POST https://<developer-service>/v1/live/payments` here.

## 1. Account (portal API)

### 1.1 Sign up

`POST /api/v1/auth/signup`

```json
{ "email": "dev@example.com", "firstName": "Wanjiku", "middleName": "Grace", "lastName": "Kamau",
  "password": "a long passphrase", "country": "KE", "phoneNumber": "+254 700 123 456", "companyName": "Kamau Ventures Ltd",
  "acceptTerms": true, "marketingOptIn": false }
```

| Field | Required | Rules |
|---|---|---|
| `email` | yes | Valid address, up to 160 characters. Stored in lower case. |
| `firstName` | yes | Up to 60 characters. |
| `middleName` | no | Up to 60 characters. Blank means none. |
| `lastName` | yes | Up to 60 characters. |
| `password` | yes | At least 10 characters (`developer.security.password-min-length`), and it may not equal the email. |
| `country` | yes | 2 letter country code, stored in upper case. |
| `phoneNumber` | no | 7 to 15 digits with an optional leading `+`. Spaces, dots, dashes and brackets are removed. Blank means none. |
| `companyName` | no | Up to 160 characters. |
| `acceptTerms` | yes | Must be `true`. The time is stored as `termsAcceptedAt`. |
| `marketingOptIn` | no | Defaults to `false`. |

Names may contain letters from any alphabet, spaces, hyphens, apostrophes and full stops (Anne-Marie, O'Brien, van der Merwe). Digits, symbols and markup are refused. There is no username field: one is generated (section 1.3) and the developer changes it on the profile.

Returns `201` with no token. Sign up only creates the account; the developer signs in on the next screen (section 1.2), and sign in does not wait for the email address to be confirmed. The portal shows `data.message` and sends the developer to the sign in page.

```json
{ "success": true, "message": "Your account has been created. Sign in to continue. We sent a confirmation link to dev@example.com. Confirm it before you create your business.",
  "data": { "message": "Your account has been created. Sign in to continue. We sent a confirmation link to dev@example.com. Confirm it before you create your business.",
            "next": "SIGN_IN", "confirmationEmailSent": true,
            "developer": { "developerId": "dev_...", "email": "dev@example.com", "username": "wanjiku.kamau",
                 "firstName": "Wanjiku", "middleName": "Grace", "lastName": "Kamau", "fullName": "Wanjiku Grace Kamau",
                 "country": "KE", "phoneNumber": "+254700123456", "companyName": "Kamau Ventures Ltd",
                 "addressLine1": null, "addressLine2": null, "city": null, "stateOrRegion": null,
                 "postalCode": null, "timezone": null, "marketingOptIn": false, "termsAcceptedAt": "...", "profileComplete": true,
                 "status": "ACTIVE", "merchantId": null, "emailVerified": false, "hasPassword": true,
                 "providers": [], "createdAt": "...", "lastLoginAt": null } },
  "timestamp": "..." }
```

`next` is always `SIGN_IN`. `confirmationEmailSent` is true when the service has a mail server and handed the confirmation email to it, and false when no mail server is configured (the message then does not mention an email). It is not a delivery guarantee: sending happens in the background and a failure never fails sign up. `emailVerified` is false until the developer uses the link in the confirmation email (section 1.7). `hasPassword` is false for an account created through Google, Facebook or GitHub that has not set a password. `providers` lists the sign in providers linked to the account, for example `["google"]`.

`profileComplete` is false until the account has a first name, a last name and a country, which an account created through Google, Facebook or GitHub may lack; complete it with `PUT /api/v1/me` (section 1.3).

Errors: `400 VALIDATION_FAILED` (the `field` names the input, for example `acceptTerms` or `country`), `409 EMAIL_TAKEN`. Sign up never fails because the confirmation email could not be sent.

### 1.2 Sign in

`POST /api/v1/auth/login` with `{ "email", "password" }`. Returns `200` with the session: `accessToken`, `tokenType`, `expiresIn` and the `developer` object. Sign up issues no token, so this is where the portal gets the first one after sign up.

Errors: `401 INVALID_CREDENTIALS` (the same for an unknown email, a wrong password and an account that has no password because it was created through a provider), `403 ACCOUNT_DISABLED`, `423 ACCOUNT_LOCKED` after 5 failed attempts, for 15 minutes. An unconfirmed email does not stop sign in; it stops creating a business (section 1.4).

### 1.3 Me

* `GET /api/v1/me` returns the developer object above.
* `PUT /api/v1/me` updates the profile. Every field is optional and only the ones sent change; nothing changes unless every field sent is valid. Returns the developer object.

  | Field | Rules |
  |---|---|
  | `firstName`, `lastName` | As at sign up. Cannot be cleared. |
  | `middleName` | As at sign up. Empty string clears it. |
  | `username` | See below. Cannot be cleared. |
  | `country` | 2 letter code. Cannot be cleared. |
  | `phoneNumber`, `companyName` | As at sign up. Empty string clears. |
  | `addressLine1`, `addressLine2` | Up to 120 characters each. Empty string clears. |
  | `city`, `stateOrRegion` | Up to 80 characters each (state, county or province). Empty string clears. |
  | `postalCode` | 2 to 12 letters, digits, spaces or hyphens, stored in upper case. Empty string clears. |
  | `timezone` | A time zone name such as `Africa/Nairobi`; offsets like `+03:00` are refused. Empty string clears. |
  | `marketingOptIn` | `true` or `false`. |

  Free text may not contain `<`, `>` or control characters. The email address cannot be changed here. `fullName` is rebuilt from the names whenever one changes. Error `400 VALIDATION_FAILED` with the `field` named.

* **Username.** Generated at sign up as `first.last` in lower case with accents removed (`José Müller` becomes `jose.muller`), falling back to the start of the email address for names without Latin letters. If it is taken, 4 random digits are added. Accounts made before usernames existed get one the next time they sign in. The developer may change it with `PUT /api/v1/me`:
  * 3 to 30 characters, lower case ASCII letters and digits, starting with a letter, with single dots, hyphens or underscores between parts and none at the end. Input is trimmed and lower cased. ASCII only keeps look alike letters from other alphabets out.
  * Reserved words are refused (admin, root, support, api, dev and similar), and so is any name that contains `premisave`, `safaricom` or `mpesa`. Separators do not hide them: `ad.min` and `pre-misave` are refused too. A generated username never is or contains a reserved word.
  * Unique, whatever the case. A name that is taken is `409 USERNAME_TAKEN`. The check and the save are separate steps, so the unique database index settles two people choosing the same name at the same moment, and the loser also gets `USERNAME_TAKEN`.
  * Changeable once every 7 days; the first change after sign up is free. Too soon is `429 RATE_LIMITED` with `retryAfterSeconds`. Sending the current username again changes nothing.
* `GET /api/v1/me/username-available?username=...` (signed in) returns `{ "username", "available", "reason" }`, with `reason` one of `OK`, `CURRENT`, `TAKEN`, `RESERVED` or `INVALID` and a `message` for the last two. It needs a bearer token so that usernames cannot be listed anonymously; there is no per account rate limit on it, so put one at the gateway.
* `PUT /api/v1/me/password` with `{ "currentPassword", "newPassword" }` changes the password, raises the token version (all earlier tokens stop working), sends a "your password was changed" notice to the account's address and returns a fresh session in the same shape as sign in. Error `401 INVALID_CREDENTIALS` if the current password is wrong. An account that has no password yet (created through a provider) leaves `currentPassword` out and sets its first password this way.

### 1.4 Business

A developer has one business, which is a merchant in the wallet service.

* `POST /api/v1/merchant` creates it. Body: `businessName` (required), `displayName`, `country` (2 letters, required), `contactEmail` (defaults to the sign in email), `contactPhone`, `defaultCurrency` (3 letters), `requestedMethods` (array of method names). Returns `201` with the wallet's merchant view (merchantId, merchantNumber, statuses, limits, enabled methods). Errors: `403 EMAIL_NOT_VERIFIED` until the developer's email address is confirmed (switch off with `developer.mail.require-verified-for-business=false`), `409 MERCHANT_EXISTS` when the account already has one.
* `GET /api/v1/merchant` returns the same view. Error `409 MERCHANT_REQUIRED` before the business exists.
* `GET /api/v1/meta/test-merchant-numbers` returns `{ "testMerchantNumbers": [...], "default": "PRM-TEST-0001" }` for sandbox apps.

### 1.5 Onboarding application

`/api/v1/merchant/application/**` is a pass-through to the wallet's onboarding application endpoints for the developer's own merchant (`COMMERCIAL_STAGE7_ONBOARDING.md`, section "Merchant side"). Paths after `/application` and bodies are the same as the wallet's `/merchants/{merchantId}/application...` routes, with the merchant id filled in for you. Document upload accepts up to 12 MB. Staff review is not reachable here.

### 1.6 Sign out

* `POST /api/v1/me/logout` ends the token that made the call. The token carries a unique id (`jti`); the id is stored until the token would have expired anyway, and from then on the token is refused with `401 UNAUTHENTICATED`. Other devices stay signed in. Returns `200` with an empty `data`.
* `POST /api/v1/me/logout-all` raises the account's token version, which ends every token issued so far on every device, including the caller's. Returns `200` with an empty `data`.

Both need a valid bearer token. Signing out twice with the same token is `401` the second time.

### 1.7 Email confirmation

Sign up sends a link to `{developer.mail.portal-base-url}/verify-email?token=...`. The link is good for 24 hours (`developer.mail.verification-ttl-hours`) and works once.

* `POST /api/v1/auth/verify-email` with `{ "token" }` (no sign in needed). Returns `{ "emailVerified": true, "email": "..." }`. Error `400 INVALID_TOKEN` for a token that is unknown, expired, already used or belongs to an address the account no longer has. Using a link for an already confirmed account succeeds the first time and is `INVALID_TOKEN` after that, because the link is spent.
* `POST /api/v1/auth/resend-verification` with `{ "email" }` (no sign in needed). Always answers `200` with the same message whether or not the address has an account, whether or not it is already confirmed, and whether or not a link was actually sent, so it cannot be used to find out who has an account.
* `POST /api/v1/me/resend-verification` (signed in). Returns `{ "sent": true, "email": "..." }`. Errors: `409 INVALID_STATE` when the address is already confirmed, `429 RATE_LIMITED` with `retryAfterSeconds` in `data` (and a `Retry-After` header) when asked too soon.

Limits per account: one link per 60 seconds (`resend-cooldown-seconds`) and 5 per hour (`max-per-hour`). Older links stay valid until they expire or are used; each is single use.

### 1.8 Forgot and reset password

* `POST /api/v1/auth/forgot-password` with `{ "email" }`. Always answers `200` with the same message. If the address belongs to an active account, a link to `{portal-base-url}/reset-password?token=...` is sent. It is good for 60 minutes (`reset-ttl-minutes`), works once, and the same limits as above apply per account.
* `POST /api/v1/auth/reset-password` with `{ "token", "newPassword" }`. The password rules of sign up apply and are checked before the link is spent, so a weak password can be corrected and the same link tried again. On success the new password is set, every existing session ends, a lockout is cleared, the email address counts as confirmed (the link proves the mailbox), all other reset links stop working, and a "your password was changed" notice is sent. The response does not sign the developer in; the portal sends them to the sign in page. Errors: `400 INVALID_TOKEN`, `400 VALIDATION_FAILED`, `403 ACCOUNT_DISABLED`.

An account created through a provider can use this flow to add a password.

### 1.9 Sign up and sign in with Google, Facebook or GitHub

Authorization code flow. The portal never handles a provider secret; this service holds the client secrets and talks to the providers.

1. `GET /api/v1/auth/oauth/providers` returns `{ "providers": ["google", "github"] }`, the providers that are configured. Use it to decide which buttons to show.
2. `GET /api/v1/auth/oauth/{provider}/authorize?redirectUri=<portal callback address>` returns `{ "provider", "authorizationUrl", "state" }`. `provider` is `google`, `facebook` or `github`. The portal stores `state` (for example in session storage) and sends the browser to `authorizationUrl`.
3. The provider sends the browser back to `redirectUri` with `code` and `state` in the query string. The portal checks that `state` equals the stored one, then:
4. `POST /api/v1/auth/oauth/{provider}/callback` with `{ "code", "state", "redirectUri" }`. `redirectUri` must be exactly the value used in step 2. The response is the sign in session of section 1.2 plus `newAccount` (true when this call created the account) and `provider`.

`redirectUri` must appear in `developer.oauth.allowed-redirect-uris` (exact match, no wildcards); otherwise `400 VALIDATION_FAILED`. The state is signed by this service, expires after 10 minutes and is bound to the provider and the redirect address, so a state from one provider or address is refused for another (`401 OAUTH_FAILED`).

How an account is chosen:

| Situation | Result |
|:-|:-|
| The provider identity has signed in before | That account |
| No account has the provider's email | A new account with no password. It is confirmed at once if the provider vouches for the address (Google, GitHub) and unconfirmed with a confirmation email if not (Facebook) |
| An account with that email exists and the provider vouches for the address | The identity is linked to that account and the developer is signed in. If the existing account had never confirmed its address, its password is removed and its older sessions end (someone may have signed up with this address without owning it); the provider's proof confirms the address |
| An account with that email exists and the provider does not vouch for the address (Facebook) | `409 ACCOUNT_EXISTS`. Nothing is linked. The developer signs in with the existing method |
| The provider gives no usable email address | `422 OAUTH_EMAIL_REQUIRED` |
| The account is disabled | `403 ACCOUNT_DISABLED` |

GitHub: only verified addresses from the account's email list are used, the primary one preferred. Facebook: the address is never treated as verified.

Errors: `400 VALIDATION_FAILED` (redirect address), `401 OAUTH_FAILED` (bad or reused code, bad state, provider refused), `404 OAUTH_NOT_CONFIGURED` (unknown provider name, or a provider without client credentials), `409 ACCOUNT_EXISTS`, `422 OAUTH_EMAIL_REQUIRED`, `503 UPSTREAM_UNAVAILABLE` (provider not reachable).

### 1.10 What the portal does

* Routes `/verify-email` and `/reset-password` read `token` from the query string, immediately remove it from the address bar (`history.replaceState`) so it does not stay in history or leak through a Referer header, then POST it. Serve those pages with `Referrer-Policy: no-referrer`.
* Keeps the OAuth `state` from step 2 and compares it with the one the provider returns before calling the callback endpoint.
* Registers each callback address with the provider (Google Cloud console, Facebook developer app, GitHub OAuth app) exactly as listed in `developer.oauth.allowed-redirect-uris`.
* After sign up, shows `data.message` and goes to the sign in page, because sign up issues no token. The developer can sign in at once, before confirming the email.
* Keeps the access token in memory or in a cookie of its own choosing; this service only reads the `Authorization: Bearer` header.

### 1.11 The emails the service sends

Every email is an HTML page built from a file in `src/main/resources/email/` (`layout.html` is the frame, the others are the bodies). Previews of all five are in `email-previews/`; open them in a browser.

| Email | When | Contains a link |
|---|---|---|
| Confirm your Premisave developer account | Sign up with email and password, a social sign up whose address the provider did not vouch for, and every resend | Yes, single use, 24 hours |
| Welcome to Premisave | The moment the address becomes confirmed (using the link, or a social sign up whose address the provider vouched for) | Portal address only |
| New sign in to your account | Every successful sign in, by password or by a provider, except the one that creates the account. Shows time (UTC), method, device and network address. Off with `developer.mail.notify-sign-in=false` | Portal "secure my account" address only, never a sign in link |
| Reset your password | `forgot-password` for an active account | Yes, single use, 60 minutes |
| Your password was changed | After a password change or a reset | Portal "reset my password" address only |

The device is read from the `User-Agent` header and the address from the connection; neither is stored. Behind a reverse proxy set `server.forward-headers-strategy=native` so the address is the caller's and not the proxy's. A missing or odd header reads as "Unknown device".

Values put into a page are HTML escaped, so a name such as `<script>` cannot inject markup. To change the wording or the look, edit the files; placeholders are written `{{name}}`. The pages use tables and inline styles because mail programs ignore most other CSS, and load no images or scripts. They carry no plain text part, and the brand is text only: add a logo by putting an `<img>` with an absolute `https` address in `layout.html`.

## 2. Apps (portal API)

An app belongs to one environment, `sandbox` or `production`, and has its own keys.

### 2.1 App object

```json
{ "appId": "app_...", "merchantId": "mer_...", "environment": "sandbox", "name": "Checkout",
  "description": null, "merchantNumber": "PRM-TEST-0001",
  "premisaveWallet": { "walletId": "...", "accountNumber": "..." },
  "status": "ACTIVE", "suspendedBy": "DEVELOPER", "suspensionReason": "...",
  "enabledMethods": ["CARD", "MOBILE_MONEY"], "limits": { "singleTransactionUsd": "100.00", "dailyVolumeUsd": null,
  "monthlyVolumeUsd": null, "dailyPayoutCount": null }, "callbackUrl": "https://example.com/hooks",
  "createdAt": "...", "updatedAt": "..." }
```

`premisaveWallet` appears on production apps only. `suspendedBy` and `suspensionReason` appear only while suspended. `enabledMethods` and `limits` are `null` when the app does not narrow the merchant.

### 2.2 Create

`POST /api/v1/apps`, `201`.

| Field | Rule |
|:-|:-|
| `name` | Required, up to 120 |
| `description` | Optional, up to 500 |
| `environment` | Required: `sandbox` or `production` |
| `merchantNumber` | Production: required, must be the business's own `PRM-` number. Sandbox: optional, one of the test numbers (default is the first) |
| `premisaveWalletAccount` | Production: required, the wallet's account number or id. Sandbox: refused |
| `enabledMethods` | Optional. Must be within the merchant's methods (production) |
| `limits` | Optional `singleTransactionUsd`, `dailyVolumeUsd`, `monthlyVolumeUsd`, `dailyPayoutCount`. Must fit within the merchant's limits (production) |
| `callbackUrl` | Optional. https only, http allowed in sandbox |

Method names are `MOBILE_MONEY`, `CARD`, `BANK_TRANSFER`, `PAYPAL`, `CRYPTO` and `PREMISAVE_WALLET`.

The response is the app object plus `apiKey`, the first key with its `secret`, shown once.

Errors: `400 VALIDATION_FAILED`, `403 LIVE_NOT_ENABLED`, `403 MERCHANT_SUSPENDED`, `404 WALLET_NOT_FOUND`, `409 MERCHANT_REQUIRED`, `409 WALLET_ALREADY_LINKED`, `422 WALLET_NOT_USABLE`, `422 LIMIT_REACHED` (10 apps per environment).

### 2.3 Other app calls

| Call | Notes |
|:-|:-|
| `GET /api/v1/apps` | Filters `environment`, `status` (`ACTIVE` or `SUSPENDED`), `page`, `size` |
| `GET /api/v1/apps/{appId}` | `404 APP_NOT_FOUND` for an unknown app or another developer's app |
| `PUT /api/v1/apps/{appId}` | Change `name`, `description`, `enabledMethods`, `limits`, `callbackUrl`. Omitted fields are unchanged. Environment, merchant number and wallet cannot change |
| `POST /api/v1/apps/{appId}/suspend` | Optional body `{ "reason" }`. While suspended the app can read but not create or change anything |
| `POST /api/v1/apps/{appId}/reactivate` | `403 FORBIDDEN` if staff suspended it |

Each change reaches the wallet within the 30 second sync interval at the latest, and immediately on the next request that app makes.

## 3. API keys (portal API)

| Call | Notes |
|:-|:-|
| `GET /api/v1/apps/{appId}/keys` | `data.keys`, without secrets. Each key shows `status` (`ACTIVE`, `ROTATING`, `REVOKED`), `secretEndsWith`, `expiresAt`, `lastUsedAt` |
| `POST /api/v1/apps/{appId}/keys` | Optional `{ "label" }` (up to 60). `201`. The response carries `key` (`pk_live_...` or `pk_sandbox_...`) and `secret` (`sk_...`), shown once. `422 LIMIT_REACHED` beyond 5 active keys |
| `POST /api/v1/apps/{appId}/keys/{key}/rotate` | Issues a new key and secret. The old key becomes `ROTATING` and stops working at `expiresAt` (24 hours by default). `404 KEY_NOT_FOUND`, `409 INVALID_STATE` unless the key is `ACTIVE`. With `developer.keys.rotation-grace-hours=0` the old key is revoked at once |
| `DELETE /api/v1/apps/{appId}/keys/{key}` | Revokes at once |

## 4. Public API

`/v1/live/**` and `/v1/sandbox/**`. Everything after the environment is the wallet's commercial API.

Methods accepted: GET, POST, PUT, PATCH and DELETE. Other methods (OPTIONS, TRACE) are not forwarded to the wallet; the same list applies to `/api/v1/merchant/application/**`.

Authenticate with either:

```
Authorization: Basic base64(key:secret)
```

or the two headers `X-Client-Key: pk_live_...` and `X-Client-Secret: sk_live_...`. A `pk_sandbox_` key works on `/v1/sandbox` only, and a `pk_live_` key on `/v1/live` only (`403 ENVIRONMENT_MISMATCH`).

Request headers passed on: `Content-Type`, `Accept`, `Accept-Language`, `Idempotency-Key`. Everything else, including the credentials, is dropped. Response headers passed back: `Content-Type`, `Content-Disposition`, `Idempotent-Replayed`, `Retry-After` and `ETag`, plus `X-RateLimit-Remaining` and `X-Request-Id` from this layer.

Errors from this layer:

| HTTP | Code | When |
|:-|:-|:-|
| 400 | `VALIDATION_FAILED` | Path contains characters that are not allowed |
| 401 | `INVALID_API_KEY` | Missing credentials, unknown key, wrong secret, revoked or expired key |
| 403 | `ENVIRONMENT_MISMATCH` | Key environment differs from the path |
| 403 | `ACCOUNT_DISABLED` | The developer account was disabled by staff |
| 404 | `NOT_FOUND` | Nothing after the environment |
| 413 | `PAYLOAD_TOO_LARGE` | Body above 1 MB |
| 429 | `RATE_LIMITED` | Over the per key limit. `Retry-After` header |
| 503 | `UPSTREAM_UNAVAILABLE` | Wallet not reachable |
| 504 | `UPSTREAM_TIMEOUT` | Wallet slow. Repeat with the same `Idempotency-Key` |

An app that staff or the developer suspended gets `403 APP_SUSPENDED` from the wallet on anything except reads.

## 5. Admin API

Header `X-API-Key: <DEVELOPER_INTERNAL_API_KEY>`. With no key configured every call returns `401`.

| Call | Body | Notes |
|:-|:-|:-|
| `GET /internal/admin/apps/{appId}` | none | Any app |
| `PUT /internal/admin/apps/{appId}/status` | `{ "status": "ACTIVE" or "SUSPENDED", "reason" }` | `reason` required to suspend. A suspension from here is recorded as `suspendedBy: ADMIN` and the developer cannot lift it |
| `PUT /internal/admin/developers/{developerId}/status` | `{ "status": "ACTIVE" or "DISABLED" }` | A disabled developer cannot sign in, their portal tokens are refused with `403 ACCOUNT_DISABLED`, and their keys are refused on `/v1` with the same code |

## 6. Error codes

`VALIDATION_FAILED` 400, `INVALID_TOKEN` 400, `UNAUTHENTICATED` 401, `OAUTH_FAILED` 401, `INVALID_CREDENTIALS` 401, `INVALID_API_KEY` 401, `ENVIRONMENT_MISMATCH` 403, `ACCOUNT_DISABLED` 403, `EMAIL_NOT_VERIFIED` 403, `FORBIDDEN` 403, `APP_SUSPENDED` 403, `MERCHANT_SUSPENDED` 403, `LIVE_NOT_ENABLED` 403, `NOT_FOUND` 404, `OAUTH_NOT_CONFIGURED` 404, `APP_NOT_FOUND` 404, `KEY_NOT_FOUND` 404, `MERCHANT_NOT_FOUND` 404, `WALLET_NOT_FOUND` 404, `EMAIL_TAKEN` 409, `USERNAME_TAKEN` 409, `ACCOUNT_EXISTS` 409, `MERCHANT_EXISTS` 409, `MERCHANT_REQUIRED` 409, `WALLET_ALREADY_LINKED` 409, `INVALID_STATE` 409, `DUPLICATE_REQUEST` 409, `PAYLOAD_TOO_LARGE` 413, `WALLET_NOT_USABLE` 422, `LIMIT_REACHED` 422, `OAUTH_EMAIL_REQUIRED` 422, `ACCOUNT_LOCKED` 423, `RATE_LIMITED` 429, `INTERNAL_ERROR` 500, `UPSTREAM_UNAVAILABLE` 503, `UPSTREAM_TIMEOUT` 504.
