# oauth-test

A small page for trying Google, Facebook and GitHub sign up and sign in against the Premisave developer service.

The browser gets the provider token. The page then posts it as JSON to one endpoint per provider:

| Provider | Endpoint | Body | What the page gets from the provider |
|----------|----------|------|--------------------------------------|
| Google | `POST /api/v1/auth/social/google` | `{ "token": "<ID token>" }` | An ID token from Google Identity Services |
| Facebook | `POST /api/v1/auth/social/facebook` | `{ "token": "<access token>" }` | A user access token from the Facebook SDK, or one pasted from the Graph API Explorer |
| GitHub | `POST /api/v1/auth/social/github` | `{ "token": "<code>" }` | A one-time code from the GitHub redirect |

GitHub is the odd one out. A browser cannot get a GitHub access token without the client secret, so the page gets a one-time code and the service trades it. The secret stays in the service.

## Run it

1. Start the developer service (port 8091).
2. In this folder run `python3 -m http.server 3000`.
3. Open `http://localhost:3000/social-login-test.html`.

Opening the file directly will not work, because the providers need a real origin.

## Backend .env

The page shows the `DEVELOPER_PORTAL_ORIGINS` line in its Configuration panel with a Copy button.

```
DEVELOPER_PORTAL_ORIGINS=http://localhost:3000
GOOGLE_CLIENT_ID=
FACEBOOK_CLIENT_ID=
FACEBOOK_CLIENT_SECRET=
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
```

Google needs only the client ID, because its ID token is checked against Google and against that ID. Facebook and GitHub need the ID and the secret. A provider with missing values stays off, and its button is disabled.

The page reads `GET /api/v1/auth/social/providers`, which returns the switched-on providers and their public client IDs, so there is nothing to paste into the page.

## Register the page with each provider

* Google: Cloud Console, Credentials, OAuth client (Web). Add `http://localhost:3000` under Authorized JavaScript origins. No redirect URI is needed.
* Facebook: App, Facebook Login. Keep the app in development mode and sign in with an app admin or tester account. If the login popup is refused on an http page, make a user token in the Graph API Explorer with the email permission and paste it into the page.
* GitHub: Settings, Developer settings, OAuth Apps, Authorization callback URL. Use exactly `http://localhost:3000/social-login-test.html`. The Configuration panel shows it with a Copy button.

## Modes

* Sign in to Premisave: runs the whole flow and shows the session the service issues, then lets you call `GET /api/v1/me` and `POST /api/v1/me/logout`.
* A new Facebook sign in creates the account as PENDING, sends a confirmation email and shows `ACCOUNT_PENDING` instead of a session, because Facebook does not vouch for the address. Open the link in the email, then sign in with Facebook again. Google and GitHub activate the account at once when they report the address as verified.
* Get the token only: stops after the provider returns, and shows the token with a ready request body, the URL and copy buttons, so you can send it from Postman. A "Send to backend now" button is there too. Tokens are short lived, and a GitHub code works only once.

## Using the tokens in Postman

The collection has one request per provider in the "00 Social sign in" folder. Paste the token into the collection variable `google_id_token`, `facebook_access_token` or `github_code`, then send the request.
