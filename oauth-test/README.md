# oauth-test

A small page for trying Google, Facebook and GitHub sign up and sign in against the Premisave developer service.

## Run it

1. Start the developer service (port 8091).
2. In this folder run `python3 -m http.server 3000`.
3. Open `http://localhost:3000/social-login-test.html`.

Opening the file directly will not work, because the providers need a real address to send you back to.

## Backend .env

The page shows the first two lines in its Configuration panel with a Copy button.

```
DEVELOPER_OAUTH_REDIRECT_URIS=http://localhost:3000/social-login-test.html
DEVELOPER_PORTAL_ORIGINS=http://localhost:3000
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
FACEBOOK_CLIENT_ID=
FACEBOOK_CLIENT_SECRET=
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
```

A provider is switched on when it has both a client ID and a secret. The page reads `GET /api/v1/auth/oauth/providers` and enables only those buttons.

## Register the return address with each provider

Use exactly `http://localhost:3000/social-login-test.html`.

* Google: Cloud Console, Credentials, OAuth client (Web), Authorized redirect URIs.
* Facebook: App, Facebook Login, Settings, Valid OAuth Redirect URIs. Keep the app in development mode and sign in with an app admin or tester account.
* GitHub: Settings, Developer settings, OAuth Apps, Authorization callback URL.

## Modes

* Sign in to Premisave: runs the whole flow and shows the session the service issues, then lets you call `GET /api/v1/me` and `POST /api/v1/me/logout`.
* A new Facebook sign in creates the account as PENDING, sends a confirmation email and shows `ACCOUNT_PENDING` instead of a session, because Facebook does not vouch for the address. Open the link in the email, then sign in with Facebook again. Google and GitHub activate the account at once when they report the address as verified.
* Get the code only: stops after the provider returns, and shows the code and state with a ready request body for `POST /api/v1/auth/oauth/{provider}/callback` in Postman. A code works once.
