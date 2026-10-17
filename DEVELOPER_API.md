# Premisave Developer Service API

Three surfaces on one service (default port 8091):

| Surface | Path | Who calls it | Authentication |
|:-|:-|:-|:-|
| Portal API | `/api/v1/**` | The developer portal (a browser app) | `Authorization: Bearer <accessToken>`, except `/api/v1/auth/**` |
| Public API | `/v1/{live\|sandbox}/**` | Merchants' servers | API key and secret |
| Admin API | `/api/v1/admin/**` and `/internal/admin/**` | Premisave staff | An administrator's `Authorization: Bearer <accessToken>`, or `X-API-Key: <DEVELOPER_INTERNAL_API_KEY>` |

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

Returns `201` with no token and no account data. Sign up only creates the account, and the account is `PENDING` until the email address is confirmed (section 1.7), which makes it `ACTIVE`. The developer cannot sign in until then (section 1.2), by password or through a provider. The portal shows `data.message` and tells the developer to open the confirmation link. A new link can be requested with `POST /api/v1/auth/resend-verification` (section 1.7).

```json
{ "success": true, "message": "Your account has been created and is pending. We sent a confirmation link to dev@example.com. Confirm your email address, then sign in.",
  "data": { "message": "Your account has been created and is pending. We sent a confirmation link to dev@example.com. Confirm your email address, then sign in.",
            "next": "CONFIRM_EMAIL", "status": "PENDING", "confirmationEmailSent": true },
  "timestamp": "..." }
```

`next` is always `CONFIRM_EMAIL`. `status` is always `PENDING`. `confirmationEmailSent` is true when the service has a mail server and handed the confirmation email to it, and false when no mail server is configured (the message then does not mention an email address). It is not a delivery guarantee: sending happens in the background and a failure never fails sign up. The account itself (`developerId`, `username`, `emailVerified` and the rest) is returned by `GET /api/v1/me` (section 1.2), not by sign up or sign in.

Account `status` values: `PENDING` (email not confirmed yet, cannot sign in), `ACTIVE`, and `DISABLED` (set by staff, blocks every sign in). A pending account becomes `ACTIVE` when the confirmation link is used, when a password is set from a reset link, or when a Google, Facebook or GitHub sign in vouches for the same address. `hasPassword` is false for an account created through a provider that has not set a password. `providers` lists the sign in providers linked to the account, for example `["google"]`.

`profileComplete` is false until the account has a first name, a last name and a country, which an account created through Google, Facebook or GitHub may lack; complete it with `PUT /api/v1/me` (section 1.3).

Errors: `400 VALIDATION_FAILED` (the `field` names the input, for example `acceptTerms` or `country`), `409 EMAIL_TAKEN`. Sign up never fails because the confirmation email could not be sent.

### 1.2 Sign in

`POST /api/v1/auth/login` with `{ "email", "password" }`. Returns `200` with the session: `accessToken`, `tokenType` and `expiresIn`. The session holds no account data; the portal reads the account with `GET /api/v1/me` (the `developer` object below, including `role`). Sign up issues no token, so this is where the portal gets the first one after sign up.

```json
{ "success": true, "message": "Signed in",
  "data": { "accessToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 3600 },
  "timestamp": "..." }
```

`GET /api/v1/me` returns the account:

```json
{ "success": true, "message": "Account retrieved",
  "data": { "developerId": "dev_...", "email": "dev@example.com", "username": "wanjiku.kamau",
            "firstName": "Wanjiku", "middleName": "Grace", "lastName": "Kamau", "fullName": "Wanjiku Grace Kamau",
            "country": "KE", "phoneNumber": "+254700123456", "companyName": "Kamau Ventures Ltd",
            "addressLine1": null, "addressLine2": null, "city": null, "stateOrRegion": null,
            "postalCode": null, "timezone": null, "marketingOptIn": false, "termsAcceptedAt": "...", "profileComplete": true,
            "status": "PENDING", "role": "DEVELOPER", "businessIds": [], "emailVerified": false, "hasPassword": true,
            "profilePictureUrl": null, "providers": [], "createdAt": "...", "lastLoginAt": "..." },
  "timestamp": "..." }
```

`status` is `PENDING` until the email is confirmed, then `ACTIVE`. `role` is `DEVELOPER` or `ADMIN`. `profilePictureUrl` is null until a picture is uploaded (section 1.3).

Errors: `401 INVALID_CREDENTIALS` (the same for an unknown email, a wrong password and an account that has no password because it was created through a provider), `403 ACCOUNT_DISABLED`, `403 ACCOUNT_PENDING` (the email address is not confirmed yet; only said when the password was right, and `data.next` is `CONFIRM_EMAIL`), `423 ACCOUNT_LOCKED` after 5 failed attempts, for 15 minutes. A pending account has no usable token, so every other portal call from it answers `403 ACCOUNT_PENDING` too.

### 1.3 Me

* `GET /api/v1/me` returns the developer object (section 1.2).
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
* **Profile picture.** `PUT /api/v1/me/profile-picture` (signed in) with the image file itself as the request body, not form data, and a `Content-Type` of `image/jpeg`, `image/png` or `image/webp`. The picture is stored in Cloudinary under `<folder>/<developerId>` and replaces any earlier one. Returns `{ "profilePictureUrl", "updatedAt" }`; the same address is `profilePictureUrl` on the account from `GET /api/v1/me` (null when there is none). The file is judged by its first bytes, whatever its name or `Content-Type` claims, and may be at most 5 MB (`CLOUDINARY_MAX_BYTES`, never more than 10 MB); Cloudinary keeps it within 1024 by 1024 pixels. Errors: `400 VALIDATION_FAILED` (`field: picture`) for an empty body or a file that is not a JPEG, PNG or WebP (form data included), `413 PAYLOAD_TOO_LARGE`, and `503 UPSTREAM_UNAVAILABLE` when Cloudinary is not set up (cloud name, key or secret empty) or cannot be reached. In a browser: `fetch(url, { method: 'PUT', headers: { Authorization: 'Bearer ...', 'Content-Type': file.type }, body: file })`.
* `DELETE /api/v1/me/profile-picture` (signed in) removes the picture from Cloudinary and the account, and returns `{ "profilePictureUrl": null, "updatedAt" }`. Repeating it is not an error. If Cloudinary cannot be reached, the picture is still cleared from the account.
* `PUT /api/v1/me/password` with `{ "currentPassword", "newPassword" }` changes the password, raises the token version (all earlier tokens stop working), sends a "your password was changed" notice to the account's address and returns a fresh session in the same shape as sign in. Error `401 INVALID_CREDENTIALS` if the current password is wrong. An account that has no password yet (created through a provider) leaves `currentPassword` out and sets its first password this way.

### 1.4 Businesses

One account can apply for many businesses. Each is a merchant in the wallet service with its own merchant number, its own onboarding application and its own apps. Applying needs a confirmed email address (switch off with `developer.mail.require-verified-for-business=false`). One account may have up to `DEVELOPER_MAX_BUSINESSES` businesses, 10 by default.

* `POST /api/v1/businesses` applies for a business and opens its application as a draft. Returns `201` with the business object below.

  | Field | Rules |
  |---|---|
  | `name` | Required, up to 160. The registered or legal name |
  | `country` | Required, 2 letter code such as `KE` |
  | `tradingName` | Optional, up to 160. The name customers know |
  | `currency` | Optional, 3 letters such as `KES` |
  | `paymentMethods` | Optional, the methods asked for: `MOBILE_MONEY`, `CARD`, `BANK_TRANSFER`, `PAYPAL`, `CRYPTO`, `PREMISAVE_WALLET` |
  | `contactEmail` | Optional, defaults to the sign in email |
  | `contactPhone` | Optional, up to 20 |

  ```json
  { "name": "Acme Limited", "tradingName": "Acme", "country": "KE", "currency": "KES",
    "paymentMethods": ["MOBILE_MONEY", "CARD"], "contactEmail": "finance@acme.example.com", "contactPhone": "254712345678" }
  ```

  Applying for a second business is the same call with another name. The same name in the same country is the same business whatever the case, spaces or punctuation: it is `409 BUSINESS_EXISTS` with `data.businessId` of the one already there. Errors: `400 VALIDATION_FAILED`, `403 EMAIL_NOT_VERIFIED`, `409 BUSINESS_EXISTS`, `422 LIMIT_REACHED`.

* `GET /api/v1/businesses` lists every business of the account: `{ "businesses": [...], "count": 2, "limit": 10 }`.
* `GET /api/v1/businesses/{businessId}` returns one. A business that is not on the account is `404 BUSINESS_NOT_FOUND`, the same as one that does not exist.
* `GET /api/v1/meta/test-merchant-numbers` returns `{ "testMerchantNumbers": [...], "default": "PRM-TEST-0001" }` for sandbox apps.

The business object:

```json
{ "businessId": "mer_...", "merchantNumber": "PRM-0000000001", "name": "Acme Limited", "tradingName": "Acme",
  "country": "KE", "currency": "KES", "paymentMethods": ["CARD", "MOBILE_MONEY"],
  "status": { "sandbox": "ACTIVE", "production": "NOT_ENABLED" },
  "limits": { "sandbox": { }, "production": { } },
  "access": "OWNER",
  "application": { "applicationId": "...", "status": "DRAFT", "readyToSubmit": false, "missing": ["registrationNumber", "directors"] },
  "nextStep": { "code": "COMPLETE_APPLICATION", "message": "Fill in 2 more items on the application, then submit it." },
  "createdAt": "..." }
```

`nextStep.code` tells a client what to show without reading the statuses: `START_APPLICATION`, `COMPLETE_APPLICATION`, `SUBMIT_APPLICATION`, `WAIT_FOR_REVIEW`, `FIX_APPLICATION` (sent back), `RESUBMIT_APPLICATION`, `CREATE_APP` (approved), `CONTACT_SUPPORT` (declined), `PRODUCTION_SUSPENDED`, `PRODUCTION_CLOSED` and `CHECK_APPLICATION`. The account object lists its businesses as `businessIds`.

`access` is `OWNER` for a business the account applied for, and `SHARED` for one it was given use of through an app transfer or a claim by merchant number (sections 2.4 and 2.5). A shared business has `application: null`, because the application belongs to the account that applied, and `nextStep.code` is `CREATE_APP` while production is on. It counts towards the account's business limit, can be named as `businessId` when creating a production app, and its merchant number is the same one the other accounts use.

### 1.5 Onboarding application

Each business has its own application under `/api/v1/businesses/{businessId}/application`. The business must be one the caller applied for (`404 BUSINESS_NOT_FOUND` if it is not on the account, `403 FORBIDDEN` if it was only shared with the account: the application stays with the account that applied). The calls go to the wallet's onboarding application endpoints for that business (`COMMERCIAL_STAGE7_ONBOARDING.md`, section "Merchant side"), with the same bodies and answers. Staff review is not reachable here.

| Call | Notes |
|:-|:-|
| `GET /application` | Status, form, documents, `returnItems`, `missing` and `readyToSubmit` |
| `PUT /application` | Save the form. Send what you have; it can be saved again until it is submitted |
| `POST /application/documents` | Upload a document, see below |
| `GET /application/documents/{documentId}/content` | Download a document |
| `DELETE /application/documents/{documentId}` | Remove a document |
| `POST /application/submit` | Send for review once nothing is missing |
| `GET /application/timeline` | What has happened to the application |

**Linking the application to a wallet, and applying for someone else's business.** Three fields on the form say whose wallet this business belongs to and who is applying. The owner of the wallet can then follow the whole application from the wallet (progress, details, decision and, once it is approved, the merchant number), so the developer does not have to report back.

| Form field | Rules |
|---|---|
| `walletAccountNumber` | Required to submit. The Premisave wallet account (the owner's email address) the business is linked to. `422` when no wallet has that number or it is frozen. An empty value removes the link while drafting. The owner is emailed when the application is submitted |
| `onBehalf` | `true` when the developer applies for a business that is not their own. Default `false` |
| `applicantRole` | Required when `onBehalf` is `true`: who the developer is to the business, for example `Developer at the agency`. Up to 80 characters |

With `onBehalf: true` an `authorisation letter` document is also required before the application can be submitted, in addition to the usual ones. `missing` lists whatever is still needed. The wallet account number is also the address that the production app and transfer codes use (see 2.2 and 2.4), and the tax pin and director ID numbers are never shown to the wallet owner in full.

**Uploading a document.** Send form data (`multipart/form-data`), not JSON:

| Form field | Rules |
|---|---|
| `file` | Required. A PDF, PNG or JPEG of at most 5 MB. The type is taken from the file when the browser sends none useful |
| `type` | Optional, what the document is, in ordinary words: `business registration`, `certificate of incorporation`, `tax compliance`, `director id`, `proof of address`, `bank statement`, `authorisation letter` or `other` (the default). `BUSINESS_REGISTRATION` and `business-registration` work too |

In a browser: `const f = new FormData(); f.append('type', 'tax compliance'); f.append('file', input.files[0]); fetch(url, { method: 'POST', headers: { Authorization: 'Bearer ...' }, body: f })`, and leave `Content-Type` for the browser to set. Errors: `400 VALIDATION_FAILED` with the `field` named (`file` or `type`), `413 PAYLOAD_TOO_LARGE`, `503` when the wallet has no document storage set up. The wallet's own JSON upload, with the file as base64, is not exposed here.

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

Each provider has its own endpoint. The browser (or the portal) gets the provider token and posts it as JSON. This service checks the token with the provider, then signs the developer in or creates the account. The portal never handles a provider secret, and this service stores no provider access token.

1. `GET /api/v1/auth/social/providers` returns `{ "providers": ["google", "github"], "clientIds": { "google": "...", "github": "..." } }`, the providers that are on and their public client IDs. Use it to decide which buttons to show and which ID to start each login with. No secret is returned.
2. The portal runs the provider login in the browser and gets:
   * Google: an ID token from Google Identity Services.
   * Facebook: a user access token from the Facebook SDK, with the `email` permission.
   * GitHub: a one-time code. The portal sends the browser to `https://github.com/login/oauth/authorize` with `client_id`, `redirect_uri`, `scope=user:email` and a random `state` that it stores and compares when GitHub sends the browser back. A browser cannot get a GitHub access token without the client secret, so it gets a code and this service trades it.
3. The portal posts the token to the provider's endpoint:

| Endpoint | Body | How the service checks it |
|:-|:-|:-|
| `POST /api/v1/auth/social/google` | `{ "token": "<ID token>" }` | Google's token info endpoint checks the signature and expiry. The token must be issued by Google to `GOOGLE_CLIENT_ID` |
| `POST /api/v1/auth/social/facebook` | `{ "token": "<access token>" }` | Facebook's `debug_token` endpoint must say the token is valid and was issued to this app, and the profile read with it must belong to the same user |
| `POST /api/v1/auth/social/github` | `{ "token": "<code>" }` | The code is traded for an access token with the client secret |

`token` is required (at most 4096 characters), otherwise `400 VALIDATION_FAILED`. The response is the sign in session of section 1.2 plus `newAccount` (true when this call created the account) and `provider`. A GitHub code works once and for about 10 minutes, so a retry needs a new login.

How an account is chosen:

| Situation | Result |
|:-|:-|
| The provider identity has signed in before | That account |
| No account has the provider's email | A new account with no password. It is confirmed at once if the provider vouches for the address (Google, GitHub) and unconfirmed with a confirmation email if not (Facebook) |
| An account with that email exists and the provider vouches for the address | The identity is linked to that account and the developer is signed in. If the existing account had never confirmed its address, its password is removed and its older sessions end (someone may have signed up with this address without owning it); the provider's proof confirms the address |
| An account with that email exists and the provider does not vouch for the address (Facebook) | `409 ACCOUNT_EXISTS`. Nothing is linked. The developer signs in with the existing method |
| The provider gives no usable email address | `422 OAUTH_EMAIL_REQUIRED` |
| The account is disabled | `403 ACCOUNT_DISABLED` |
| The account is new or still pending and the provider does not vouch for the address (Facebook) | `403 ACCOUNT_PENDING`, `data.next` is `CONFIRM_EMAIL`. On a first sign in the account is created and a confirmation link is sent; no session is issued. After the link is used, signing in with the provider again works |

GitHub: only verified addresses from the account's email list are used, the primary one preferred. Facebook: the address is never treated as verified.

Errors: `400 VALIDATION_FAILED` (missing token), `401 OAUTH_FAILED` (bad, expired or reused token or code, a token issued to another app, provider refused), `404 OAUTH_NOT_CONFIGURED` (the provider is not switched on in this service), `403 ACCOUNT_PENDING`, `409 ACCOUNT_EXISTS`, `422 OAUTH_EMAIL_REQUIRED`, `503 UPSTREAM_UNAVAILABLE` (provider not reachable).

### 1.10 What the portal does

* Routes `/verify-email` and `/reset-password` read `token` from the query string, immediately remove it from the address bar (`history.replaceState`) so it does not stay in history or leak through a Referer header, then POST it. Serve those pages with `Referrer-Policy: no-referrer`.
* For GitHub, makes a random `state` before sending the browser away, keeps it in session storage and compares it with the one GitHub returns before posting the code.
* Registers its origin with Google as an Authorized JavaScript origin, and its callback address with the GitHub OAuth app, exactly as it uses them. Facebook needs no redirect address for the SDK login.
* After sign up, shows `data.message` and goes to the sign in page, because sign up issues no token. The developer can sign in at once, before confirming the email.
* Keeps the access token in memory or in a cookie of its own choosing; this service only reads the `Authorization: Bearer` header.

### 1.11 The emails the service sends

Every email is an HTML page built from a file in `src/main/resources/email/` (`layout.html` is the frame, the others are the bodies). Previews of all nine are in `email-previews/`; open them in a browser.

| Email | When | Contains a link |
|---|---|---|
| Confirm your Premisave developer account | Sign up with email and password, a social sign up whose address the provider did not vouch for, and every resend | Yes, single use, 24 hours |
| Welcome to Premisave | The moment the address becomes confirmed (using the link, or a social sign up whose address the provider vouched for) | Portal address only |
| New sign in to your account | Every successful sign in, by password or by a provider, except the one that creates the account. Shows time (UTC), method, device and network address. Off with `developer.mail.notify-sign-in=false` | Portal "secure my account" address only, never a sign in link |
| Reset your password | `forgot-password` for an active account | Yes, single use, 60 minutes |
| Your password was changed | After a password change or a reset | Portal "reset my password" address only |
| Your Premisave confirmation code | Moving an app or taking up a business by its merchant number (2.4, 2.5). Goes to the address linked to the business, not to the developer who asked, and shows the business, the merchant number, who asked and how long the code lasts | No, a six digit code |
| A production app was transferred to your account | To the receiving account when the code is confirmed | Portal address only |
| Your production app was transferred | To the sending account when the code is confirmed, with the receiver's address in part only | Portal "secure my account" address only |

The device is read from the `User-Agent` header and the address from the connection; neither is stored. Behind a reverse proxy set `server.forward-headers-strategy=native` so the address is the caller's and not the proxy's. A missing or odd header reads as "Unknown device".

Values put into a page are HTML escaped, so a name such as `<script>` cannot inject markup. To change the wording or the look, edit the files; placeholders are written `{{name}}`. The pages use tables and inline styles because mail programs ignore most other CSS, and load no images or scripts. They carry no plain text part, and the brand is text only: add a logo by putting an `<img>` with an absolute `https` address in `layout.html`.

## 2. Apps (portal API)

An app belongs to one business and one environment, `sandbox` or `production`, and has its own keys. `merchantId` in the app object is the same value as `businessId`.

### 2.1 App object

```json
{ "appId": "app_...", "businessId": "mer_...", "merchantId": "mer_...", "environment": "sandbox", "name": "Checkout",
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
| `businessId` | The business the app is for. Optional while the account has exactly one business. With none the call is `409 BUSINESS_REQUIRED`, with several it is `400 VALIDATION_FAILED` on `businessId` and `data.businessIds` lists them |
| `name` | Required, up to 120 |
| `description` | Optional, up to 500 |
| `environment` | Required: `sandbox` or `production` |
| `merchantNumber` | Production: optional, the business's own number is used, and a different one is refused. Sandbox: optional, one of the test numbers (default is the first) |
| `premisaveWalletAccount` | Production: the wallet's account number or id. May be left out when the account applied for the business and its application names a wallet (`walletAccountNumber`): that wallet is used. Required otherwise, including for a shared business. Sandbox: refused |
| `enabledMethods` | Optional. Must be within the merchant's methods (production) |
| `limits` | Optional `singleTransactionUsd`, `dailyVolumeUsd`, `monthlyVolumeUsd`, `dailyPayoutCount`. Must fit within the merchant's limits (production) |
| `callbackUrl` | Optional. https only, http allowed in sandbox |

Method names are `MOBILE_MONEY`, `CARD`, `BANK_TRANSFER`, `PAYPAL`, `CRYPTO` and `PREMISAVE_WALLET`.

The response is the app object plus `apiKey`, the first key with its `secret`, shown once.

Errors: `400 VALIDATION_FAILED`, `403 LIVE_NOT_ENABLED`, `403 MERCHANT_SUSPENDED`, `404 BUSINESS_NOT_FOUND`, `404 WALLET_NOT_FOUND`, `409 BUSINESS_REQUIRED`, `409 INVALID_STATE` (the business has no merchant number yet), `409 WALLET_ALREADY_LINKED`, `422 WALLET_NOT_USABLE`, `422 LIMIT_REACHED` (10 apps per environment for each business).

### 2.3 Other app calls

| Call | Notes |
|:-|:-|
| `GET /api/v1/apps` | Filters `businessId`, `environment`, `status` (`ACTIVE` or `SUSPENDED`), `page`, `size` |
| `GET /api/v1/apps/{appId}` | `404 APP_NOT_FOUND` for an unknown app or another developer's app |
| `PUT /api/v1/apps/{appId}` | Change `name`, `description`, `enabledMethods`, `limits`, `callbackUrl`. Omitted fields are unchanged. Environment, merchant number and wallet cannot change |
| `POST /api/v1/apps/{appId}/suspend` | Optional body `{ "reason" }`. While suspended the app can read but not create or change anything |
| `POST /api/v1/apps/{appId}/reactivate` | `403 FORBIDDEN` if staff suspended it |

Each change reaches the wallet within the 30 second sync interval at the latest, and immediately on the next request that app makes.

### 2.4 Moving a production app to another developer account

A business is applied for once, but the same merchant number can be used to go live from another developer account when the business owner agrees. The owner agrees by sharing a six digit code, which is emailed to the address linked to the business: the wallet account named on the application, or the business contact email when no wallet is linked. The developer asking never sees the code, so the owner has to give it to them.

* `POST /api/v1/apps/{appId}/transfer` with `{ "toEmail": "colleague@example.com" }`, `201`. Starts the move and emails the code. Only a production app that belongs to the caller can move, and `toEmail` must be another active developer account that has room for one more business (`422 LIMIT_REACHED`) and is not the caller (`400 VALIDATION_FAILED`, field `toEmail`).
* `POST /api/v1/transfers/{transferId}/confirm` with `{ "otp": "123456" }`. The caller sends the code the owner shared. On success:
  * the app belongs to the other account, with the same merchant number and wallet link, and every API key it had is revoked, so the new owner makes their own and the old owner can no longer call the API with it;
  * the other account is given use of the business (`access: SHARED`), so it can create more production apps with the same merchant number;
  * the business stays on the sending account with its other apps;
  * both accounts get an email.
* `POST /api/v1/transfers/{transferId}/resend` emails a fresh code for a request still open. The old code stops working and the tries start again.
* `DELETE /api/v1/transfers/{transferId}` cancels a request that has not been confirmed. Nothing moves.
* `GET /api/v1/transfers` lists the caller's latest requests (`direction: OUTGOING`) and the apps on their way to the caller (`direction: INCOMING`). `GET /api/v1/transfers/{transferId}` returns one.

The transfer object:

```json
{ "transferId": "trf_...", "type": "APP_TRANSFER", "direction": "OUTGOING", "status": "PENDING",
  "app": { "appId": "app_...", "name": "Checkout backend" },
  "business": { "businessId": "mer_...", "name": "Acme Limited", "merchantNumber": "PRM-0000000001" },
  "to": "n***@example.com", "codeSentTo": "o****@acme.example.com", "attemptsLeft": 5,
  "expiresAt": "2026-10-17T08:15:00Z", "createdAt": "2026-10-17T08:00:00Z", "completedAt": null }
```

`status` is `PENDING`, `COMPLETED`, `CANCELLED` or `EXPIRED`. An incoming request shows `from` (the sender's address in part) instead of `to`, and no `codeSentTo` or `attemptsLeft`.

Rules for the code: it lasts 15 minutes, works once, and five wrong tries close the request. A new request for the same app replaces the old one. A developer can start one code a minute and ten requests an hour, wrong merchant numbers included. The code is stored only as a keyed hash. If the app, the other account or the business limit changed before confirming, the confirmation is refused and nothing moves.

Errors: `400 VALIDATION_FAILED`, `400 OTP_INVALID` (`data.attemptsLeft`), `410 OTP_EXPIRED`, `404 APP_NOT_FOUND`, `404 TRANSFER_NOT_FOUND`, `409 INVALID_STATE` (not a production app, no address linked to the business, or the request is already done or closed), `422 LIMIT_REACHED`, `429 RATE_LIMITED` (`data.retryAfterSeconds`).

### 2.5 Taking up a business by its merchant number

A developer account can go live with an approved business that another account applied for, without any app moving. The business owner shares the same kind of code.

* `POST /api/v1/transfers/claim` with `{ "merchantNumber": "PRM-0000000001" }`, `201`. The business must be approved with production switched on. The code is emailed to the address linked to the business.
* Confirm, resend, cancel and list work as in 2.4 (`type` is `BUSINESS_CLAIM`).
* On confirming, the account is given use of the business (`access: SHARED`) and creates a production app for it with `businessId`, using the business's own merchant number and its own wallet account (`premisaveWalletAccount`).

A number that does not exist and a business that is not live get the same answer, `404 BUSINESS_NOT_FOUND`, so the call cannot be used to find out which numbers exist. Each wrong number counts towards the limit of ten requests an hour. `409 BUSINESS_EXISTS` when the business is already on the account, `422 LIMIT_REACHED` when the account has no room for another.

## 3. API keys (portal API)

| Call | Notes |
|:-|:-|
| `GET /api/v1/apps/{appId}/keys` | `data.keys`, without secrets. Each key shows `status` (`ACTIVE`, `ROTATING`, `REVOKED`), `secretEndsWith`, `expiresAt`, `lastUsedAt` |
| `POST /api/v1/apps/{appId}/keys` | Optional `{ "label" }` (up to 60). `201`. The response carries `key` (`pk_live_...` or `pk_sandbox_...`) and `secret` (`sk_...`), shown once. `422 LIMIT_REACHED` beyond 5 active keys |
| `POST /api/v1/apps/{appId}/keys/{key}/rotate` | Issues a new key and secret. The old key becomes `ROTATING` and stops working at `expiresAt` (24 hours by default). `404 KEY_NOT_FOUND`, `409 INVALID_STATE` unless the key is `ACTIVE`. With `developer.keys.rotation-grace-hours=0` the old key is revoked at once |
| `DELETE /api/v1/apps/{appId}/keys/{key}` | Revokes at once |

## 4. Public API

`/v1/live/**` and `/v1/sandbox/**`. Everything after the environment is the wallet's commercial API.

Methods accepted: GET, POST, PUT, PATCH and DELETE. Other methods (OPTIONS, TRACE) are not forwarded to the wallet; the same list applies to the application calls in section 1.5.

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
| 403 | `ACCOUNT_PENDING` | The email address has not been confirmed yet. Open the confirmation link, or ask for a new one |
| 404 | `NOT_FOUND` | Nothing after the environment |
| 413 | `PAYLOAD_TOO_LARGE` | Body above 1 MB |
| 429 | `RATE_LIMITED` | Over the per key limit. `Retry-After` header |
| 503 | `UPSTREAM_UNAVAILABLE` | Wallet not reachable |
| 504 | `UPSTREAM_TIMEOUT` | Wallet slow. Repeat with the same `Idempotency-Key` |

An app that staff or the developer suspended gets `403 APP_SUSPENDED` from the wallet on anything except reads.

## 5. Admin API

Premisave staff control everything on the developer service from here: accounts, apps, API keys, business sharing and app transfers. Every call behaves the same on both entrances.

| Entrance | Path | Authentication | Who it is for |
|:-|:-|:-|:-|
| Staff portal | `/api/v1/admin/**` | `Authorization: Bearer <accessToken>` of an account whose `role` is `ADMIN` | Staff using the portal. A developer account gets `403 FORBIDDEN` |
| Internal | `/internal/admin/**` | `X-API-Key: <DEVELOPER_INTERNAL_API_KEY>` | Staff tooling and other services. With no key configured every call returns `401` |

In the tables below `/admin` stands for either prefix.

### 5.1 Roles

Every account has a `role`, shown in every account response: `DEVELOPER` (the default, also for accounts created before roles existed) or `ADMIN`. The role is read from the database on every request, so a demotion takes effect at once and no token has to expire first.

**The first administrator.** Set `DEVELOPER_ADMIN_EMAILS` to a comma separated list of addresses, for example `DEVELOPER_ADMIN_EMAILS=boss@premisave.com`. When an account with a listed address that has been confirmed signs in (password or social sign in), it becomes an `ADMIN`. The confirmation is what stops someone else from claiming a listed address by signing up with it. After that, administrators manage roles through `PUT /admin/developers/{developerId}/role`. An address that stays on the list is promoted again at its next sign in if the role was taken away, so remove the address from the list when you demote that person.

**Safeguards.**

| Rule | Result |
|:-|:-|
| An administrator changes their own role, disables or deletes their own account | `403 FORBIDDEN` |
| The last active administrator is demoted, disabled or deleted | `409 INVALID_STATE` |
| An account that is not `ACTIVE` is made an administrator | `409 INVALID_STATE` |
| A developer is deleted while they still have apps or applied for a business | `409 INVALID_STATE` with the counts. Move or delete the apps, or disable the account |
| An app is deleted while the wallet service cannot be told | `409 INVALID_STATE`. The app stays, suspended, and the delete can be repeated |

The self checks need to know who is calling, so they apply on the staff portal entrance. Calls with the internal key are recorded as the actor `internal-key`; the last administrator rule applies to them too.

Lists are paged with `page` (from 0) and `size` (default 20, at most 100), newest first. Filters that take an id match the whole id; `q` and `name` match anywhere in the text, ignoring case, and are never read as patterns.

### 5.2 Overview and audit

| Call | Body | Notes |
|:-|:-|:-|
| `GET /admin/stats` | none | Counts. `developers`: `total`, `pending`, `active`, `disabled`, `administrators`. `apps`: `total`, `sandbox`, `production`, `active`, `suspended`. `transfers`: `pending`, `completed`. And `generatedAt` |
| `GET /admin/audit` | query: `actorId`, `targetId`, `action`, `page`, `size` | The trail of every admin change, newest first. `actorId` and `targetId` match exactly. `action` matches anywhere in the name, so `APP_` finds every app change and `_DELETED` every delete. Each row has `id`, `actorId`, `actorEmail`, `action`, `targetType`, `targetId`, `detail` and `createdAt` |

Actions recorded: `DEVELOPER_CREATED`, `DEVELOPER_UPDATED`, `DEVELOPER_EMAIL_CHANGED`, `DEVELOPER_EMAIL_CONFIRMED`, `DEVELOPER_ROLE_CHANGED`, `DEVELOPER_ENABLED`, `DEVELOPER_DISABLED`, `DEVELOPER_UNLOCKED`, `DEVELOPER_SIGNED_OUT`, `DEVELOPER_PASSWORD_RESET_SENT`, `DEVELOPER_DELETED`, `BUSINESS_SHARED`, `BUSINESS_UNSHARED`, `APP_UPDATED`, `APP_SUSPENDED`, `APP_REACTIVATED`, `APP_OWNER_CHANGED`, `APP_DELETED`, `KEY_ISSUED`, `KEY_ROTATED`, `KEY_REVOKED`, `KEYS_REVOKED` and `TRANSFER_CANCELLED`. Entries are kept in the `admin_actions` collection and hold no secret. A failure to write an entry is logged and never undoes the change.

```json
GET /api/v1/admin/stats
{ "success": true, "message": "Statistics retrieved", "data": {
    "developers": { "total": 42, "pending": 3, "active": 37, "disabled": 2, "administrators": 2 },
    "apps": { "total": 58, "sandbox": 30, "production": 28, "active": 55, "suspended": 3 },
    "transfers": { "pending": 1, "completed": 9 },
    "generatedAt": "2026-10-18T08:00:00Z" } }
```

### 5.3 Developers

| Call | Body | Notes |
|:-|:-|:-|
| `GET /admin/developers` | query: `q`, `status`, `page`, `size` | Searches accounts, newest first. `q` is matched anywhere in the email, username, full name or company name, or against the developer id exactly (at most 100 characters). `status` is `PENDING`, `ACTIVE` or `DISABLED`. Each row has `developerId`, `email`, `username`, `fullName`, `companyName`, `country`, `role`, `status`, `emailVerified`, `businessIds`, `createdAt` and `lastLoginAt`. Use it to find the `developerId` to put on a new merchant |
| `GET /admin/administrators` | query: `page`, `size` | The accounts whose role is `ADMIN`, same rows as above |
| `POST /admin/developers` | `{ "email", "firstName", "middleName"?, "lastName", "country"?, "phoneNumber"?, "companyName"?, "role"?, "sendInvite"? }` | Creates an `ACTIVE` account with a confirmed address and no password. `role` is `DEVELOPER` (default) or `ADMIN`. Unless `sendInvite` is `false`, the person is emailed a link to choose a password; the answer says whether that email could be sent (`inviteSent`). `409 EMAIL_TAKEN` when the address is used |
| `GET /admin/developers/{developerId}` | none | The full account: everything the owner sees, plus `appliedBusinessIds`, `sharedBusinessIds`, `failedLogins`, `lockedUntil` and `updatedAt`. No password hash or token is ever shown |
| `PUT /admin/developers/{developerId}` | the profile body of `PUT /api/v1/me`, every field optional | Changes the profile of any account. The same checks as for the owner apply |
| `PUT /admin/developers/{developerId}/status` | `{ "status": "ACTIVE" or "DISABLED" }` | A disabled developer cannot sign in, their portal tokens are refused with `403 ACCOUNT_DISABLED`, and their keys are refused on `/v1` with the same code. `PENDING` cannot be set; an account leaves it by confirming its address |
| `PUT /admin/developers/{developerId}/role` | `{ "role": "DEVELOPER" or "ADMIN" }` | See the safeguards above |
| `PUT /admin/developers/{developerId}/email` | `{ "email", "verified"? }` | Changes the sign in address. `verified` defaults to `true`; with `false` a confirmation link is sent to the new address. Every session of the account ends. `409 EMAIL_TAKEN` when another account has it |
| `POST /admin/developers/{developerId}/verify-email` | none | Counts the address as confirmed. A `PENDING` account becomes `ACTIVE` |
| `POST /admin/developers/{developerId}/unlock` | none | Clears the failed sign in count and any lockout |
| `POST /admin/developers/{developerId}/sign-out` | none | Ends every session of the account on every device |
| `POST /admin/developers/{developerId}/password-reset` | none | Emails a reset link to an active account. The answer has `requested` and `emailEnabled` |
| `DELETE /admin/developers/{developerId}` | none | Removes an account that owns nothing, with its email links and linked social identities. Otherwise `409 INVALID_STATE`; disable the account instead |
| `GET /admin/developers/{developerId}/apps` | query: `environment`, `page`, `size` | The apps of one account |
| `GET /admin/developers/{developerId}/businesses` | none | The businesses of one account, each marked as applied for or shared |
| `POST /admin/developers/{developerId}/businesses/{businessId}` | none | Gives the account use of a business it did not apply for. Nothing changes if it already has it |
| `DELETE /admin/developers/{developerId}/businesses/{businessId}` | none | Takes a shared business back. `409 INVALID_STATE` for a business the account applied for, or one it still has apps on |

### 5.4 Apps and keys

| Call | Body | Notes |
|:-|:-|:-|
| `GET /admin/apps` | query: `developerId`, `businessId`, `environment` (`sandbox` or `production`), `status` (`ACTIVE` or `SUSPENDED`), `name`, `page`, `size` | Every app on the platform, filters combine |
| `GET /admin/apps/{appId}` | none | Any app |
| `PUT /admin/apps/{appId}` | `{ "name"?, "description"?, "enabledMethods"?, "limits"?, "callbackUrl"? }` | Changes any app. The rules for the owner apply, for example a production callback URL must be `https`. The wallet service is told |
| `PUT /admin/apps/{appId}/status` | `{ "status": "ACTIVE" or "SUSPENDED", "reason" }` | `reason` required to suspend. A suspension from here is recorded as `suspendedBy: ADMIN` and the developer cannot lift it |
| `PUT /admin/apps/{appId}/owner` | `{ "developerId" }` | Hands the app to another active account, which gets use of the app's business. Every key of the app is revoked, so the new owner issues their own. `409 INVALID_STATE` for an unknown, inactive or current owner |
| `DELETE /admin/apps/{appId}` | none | Suspends the app in the wallet first, then deletes it with its keys |
| `GET /admin/apps/{appId}/keys` | none | The keys of the app with status and last used time; secrets are never shown |
| `POST /admin/apps/{appId}/keys` | `{ "label"? }` | Issues a key. The secret is in the answer once and cannot be read again |
| `POST /admin/apps/{appId}/keys/{key}/rotate` | none | Issues a replacement; the old key keeps working for the rotation grace period |
| `DELETE /admin/apps/{appId}/keys/{key}` | none | Revokes one key now |
| `DELETE /admin/apps/{appId}/keys` | none | Revokes every key of the app and returns how many |

### 5.5 Transfers and business claims

| Call | Body | Notes |
|:-|:-|:-|
| `GET /admin/transfers` | query: `status` (`PENDING`, `COMPLETED`, `CANCELLED`, `REJECTED`), `type` (`APP_TRANSFER` or `BUSINESS_CLAIM`), `developerId`, `page`, `size` | Requests newest first. `developerId` matches the requester or the receiver. The code that was sent is never shown |
| `GET /admin/transfers/{transferId}` | none | One request with both parties, the app or business, attempts used and expiry |
| `DELETE /admin/transfers/{transferId}` | none | Cancels a request that is still waiting for its code. `409 INVALID_STATE` for one that is done |

## 6. Error codes

`VALIDATION_FAILED` 400, `OTP_INVALID` 400, `INVALID_TOKEN` 400, `UNAUTHENTICATED` 401, `OAUTH_FAILED` 401, `INVALID_CREDENTIALS` 401, `INVALID_API_KEY` 401, `ENVIRONMENT_MISMATCH` 403, `ACCOUNT_DISABLED` 403, `ACCOUNT_PENDING` 403, `EMAIL_NOT_VERIFIED` 403, `FORBIDDEN` 403, `APP_SUSPENDED` 403, `MERCHANT_SUSPENDED` 403, `LIVE_NOT_ENABLED` 403, `NOT_FOUND` 404, `OAUTH_NOT_CONFIGURED` 404, `APP_NOT_FOUND` 404, `KEY_NOT_FOUND` 404, `MERCHANT_NOT_FOUND` 404, `BUSINESS_NOT_FOUND` 404, `TRANSFER_NOT_FOUND` 404, `WALLET_NOT_FOUND` 404, `EMAIL_TAKEN` 409, `USERNAME_TAKEN` 409, `ACCOUNT_EXISTS` 409, `BUSINESS_EXISTS` 409, `BUSINESS_REQUIRED` 409, `WALLET_ALREADY_LINKED` 409, `INVALID_STATE` 409, `DUPLICATE_REQUEST` 409, `OTP_EXPIRED` 410, `PAYLOAD_TOO_LARGE` 413, `WALLET_NOT_USABLE` 422, `LIMIT_REACHED` 422, `OAUTH_EMAIL_REQUIRED` 422, `ACCOUNT_LOCKED` 423, `RATE_LIMITED` 429, `INTERNAL_ERROR` 500, `UPSTREAM_UNAVAILABLE` 503, `UPSTREAM_TIMEOUT` 504.