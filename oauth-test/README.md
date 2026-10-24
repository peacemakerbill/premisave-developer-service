<h1 align="center">oauth-test</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Premisave-developer%20social%20sign%20in-0b7a43?style=for-the-badge&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IiNmZmZmZmYiIHN0cm9rZS13aWR0aD0iMiIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cmVjdCB4PSIzIiB5PSIxMSIgd2lkdGg9IjE4IiBoZWlnaHQ9IjExIiByeD0iMiIvPjxwYXRoIGQ9Ik03IDExVjdhNSA1IDAgMCAxIDEwIDB2NCIvPjwvc3ZnPgo%3D" alt="Premisave developer social sign in">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Google-ID%20token-4285F4?style=for-the-badge&logo=google&logoColor=white" alt="Google ID token">
  <img src="https://img.shields.io/badge/Facebook-access%20token-1877F2?style=for-the-badge&logo=facebook&logoColor=white" alt="Facebook access token">
  <img src="https://img.shields.io/badge/GitHub-one--time%20code-181717?style=for-the-badge&logo=github&logoColor=white" alt="GitHub one-time code">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/service-developer%2C%20port%208091-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="service developer, port 8091">
  <img src="https://img.shields.io/badge/test%20pages-localhost%3A3000-0b7a43?style=flat-square" alt="test pages localhost:3000">
  <img src="https://img.shields.io/badge/themes-5-0b9a55?style=flat-square" alt="themes 5">
  <img src="https://img.shields.io/badge/build%20step-none-lightgrey?style=flat-square" alt="build step none">
  <img src="https://img.shields.io/badge/fonts-IBM%20Plex%2C%20embedded-1f6feb?style=flat-square" alt="fonts IBM Plex, embedded">
</p>
<p align="center">
  <img src="https://img.shields.io/badge/Google-ID%20token-4285F4?style=flat-square&logo=google&logoColor=white" alt="Google ID token">
  <img src="https://img.shields.io/badge/Facebook-access%20token-1877F2?style=flat-square&logo=facebook&logoColor=white" alt="Facebook access token">
  <img src="https://img.shields.io/badge/GitHub-one--time%20code-181717?style=flat-square&logo=github&logoColor=white" alt="GitHub one-time code">
</p>

<p align="center">
  <a href="#run-it">Run it</a> &nbsp;|&nbsp;
  <a href="#backend-env">Backend env</a> &nbsp;|&nbsp;
  <a href="#register-the-page-with-each-provider">Register</a> &nbsp;|&nbsp;
  <a href="#modes">Modes</a> &nbsp;|&nbsp;
  <a href="#themes-and-fonts">Themes</a> &nbsp;|&nbsp;
  <a href="#using-the-tokens-in-postman">Postman</a> &nbsp;|&nbsp;
  <a href="#when-something-is-off">Troubleshooting</a>
</p>

Pages for trying Google, Facebook and GitHub sign up and sign in against the Premisave developer service.

## ![][i-layers] Two ways to run the same flow

| Page | What it is |
|------|------------|
| `social-login-test.html` | A standalone page that does only social sign in. Use it when you want nothing else on screen. |
| `developer-console.html`, Social sign in page | The same flow inside the developer console. A successful login is added as an account in the top bar, so you can carry straight on with businesses, apps and API keys as that user. |

Both pages share the same themes and fonts, and both read the same service endpoints.

The browser gets the provider token. The page then posts it as JSON to one endpoint per provider:

| Provider | Endpoint | Body | What the page gets from the provider |
|----------|----------|------|--------------------------------------|
| <img src="https://img.shields.io/badge/-Google-4285F4?style=flat-square&logo=google&logoColor=white" alt="Google"> | `POST /api/v1/auth/social/google` | `{ "token": "<ID token>" }` | An ID token from Google Identity Services |
| <img src="https://img.shields.io/badge/-Facebook-1877F2?style=flat-square&logo=facebook&logoColor=white" alt="Facebook"> | `POST /api/v1/auth/social/facebook` | `{ "token": "<access token>" }` | A user access token from the Facebook SDK, or one pasted from the Graph API Explorer |
| <img src="https://img.shields.io/badge/-GitHub-181717?style=flat-square&logo=github&logoColor=white" alt="GitHub"> | `POST /api/v1/auth/social/github` | `{ "token": "<code>" }` | A one-time code from the GitHub redirect |

> [!IMPORTANT]
> GitHub is the odd one out. A browser cannot get a GitHub access token without the client secret, so the page gets a one-time code and the service trades it. The secret stays in the service.

## ![][i-play] Run it

1. Start the developer service (port 8091).
2. In this folder run `python3 -m http.server 3000`.
3. Open one of:
   * `http://localhost:3000/social-login-test.html`
   * `http://localhost:3000/developer-console.html`, then Social sign in in the left menu, or the "Continue with Google, Facebook or GitHub" button on the sign in screen.

> [!WARNING]
> Opening a file directly will not work, because the providers need a real origin.

The developer service address defaults to `http://localhost:8091`. Change it under Configuration on the standalone page, or under Connection in the console.

## ![][i-sliders] Backend env

Both pages show the `DEVELOPER_PORTAL_ORIGINS` line with a Copy button: in the Configuration panel of the standalone page, and under "Setup values for the provider consoles" on the console's Social sign in page.

```
DEVELOPER_PORTAL_ORIGINS=http://localhost:3000
GOOGLE_CLIENT_ID=
FACEBOOK_CLIENT_ID=
FACEBOOK_CLIENT_SECRET=
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
```

| Provider | Needs in the service |
|----------|----------------------|
| <img src="https://img.shields.io/badge/-Google-4285F4?style=flat-square&logo=google&logoColor=white" alt="Google"> | Client ID only, because its ID token is checked against Google and against that ID |
| <img src="https://img.shields.io/badge/-Facebook-1877F2?style=flat-square&logo=facebook&logoColor=white" alt="Facebook"> | Client ID and client secret |
| <img src="https://img.shields.io/badge/-GitHub-181717?style=flat-square&logo=github&logoColor=white" alt="GitHub"> | Client ID and client secret |

A provider with missing values stays off, and its button is disabled with a note saying which values to set.

The pages read `GET /api/v1/auth/social/providers`, which returns the switched-on providers and their public client IDs, so there is nothing to paste into either page. The console also uses this call for the "Social sign in" count on its sign in screen.

## ![][i-file] Register the page with each provider

**<img src="https://img.shields.io/badge/-Google-4285F4?style=flat-square&logo=google&logoColor=white" alt="Google">** Cloud Console, Credentials, OAuth client (Web). Add `http://localhost:3000` under Authorized JavaScript origins. No redirect URI is needed.

**<img src="https://img.shields.io/badge/-Facebook-1877F2?style=flat-square&logo=facebook&logoColor=white" alt="Facebook">** App, Facebook Login. Keep the app in development mode and sign in with an app admin or tester account. If the login popup is refused on an http page, make a user token in the Graph API Explorer with the email permission and paste it into the page.

**<img src="https://img.shields.io/badge/-GitHub-181717?style=flat-square&logo=github&logoColor=white" alt="GitHub">** Settings, Developer settings, OAuth Apps, Authorization callback URL. Use the exact address of the page you start from, because GitHub sends the person back to it:

| Starting page | Callback URL to register |
|---------------|--------------------------|
| Standalone page | `http://localhost:3000/social-login-test.html` |
| Console | `http://localhost:3000/developer-console.html` |

Each page shows its own callback URL with a Copy button. To use both pages, register one OAuth App per callback URL, or switch the callback URL on the app when you change pages.

## ![][i-toggle] Modes

| Mode | What happens |
|------|--------------|
| <img src="https://img.shields.io/badge/-Sign%20in%20to%20Premisave-0b7a43?style=flat-square" alt="Sign in to Premisave"> | Runs the whole flow and shows the session the service issues. On the standalone page you can then call `GET /api/v1/me` and `POST /api/v1/me/logout`. In the console the session also becomes an account in the top bar, with its role and status, and Profile, Sessions and the rest of the console work as that user. |
| <img src="https://img.shields.io/badge/-Get%20the%20token%20only-1f6feb?style=flat-square" alt="Get the token only"> | Stops after the provider returns, and shows the token with a ready request body, the URL and copy buttons, so you can send it from Postman. A button to send it to the service now is there too. Tokens are short lived, and a GitHub code works only once. |

> [!NOTE]
> A new Facebook sign in creates the account as PENDING, sends a confirmation email and shows `ACCOUNT_PENDING` instead of a session, because Facebook does not vouch for the address. Open the link in the email, then sign in with Facebook again. Google and GitHub activate the account at once when they report the address as verified.

The page keeps the chosen mode and any kept tokens for the browser tab, so they survive the redirect to GitHub and back. A code that comes back from GitHub without a matching state, or in a tab where no GitHub login was started, is rejected and the reason is shown in the progress panel.

## ![][i-activity] Progress and logs

The progress line shows where a login is: Approve, Provider token, Premisave checks it, Session token.

| Step state | Meaning |
|------------|---------|
| <img src="https://img.shields.io/badge/-Waiting-8a919b?style=flat-square" alt="Waiting"> | Not reached yet |
| <img src="https://img.shields.io/badge/-Active-1f6feb?style=flat-square" alt="Active"> | In progress, for example the provider popup is open |
| <img src="https://img.shields.io/badge/-Done-0b7a43?style=flat-square" alt="Done"> | The step succeeded |
| <img src="https://img.shields.io/badge/-Failed-b3261e?style=flat-square" alt="Failed"> | The step was refused, with the service message shown below the line |

* The standalone page has its own Request log card.
* In the console every call goes to the console's request log drawer (the list icon in the top bar), where each one can be opened, copied as cURL or sent to the request builder.

## ![][i-palette] Themes and fonts

Five themes: <img src="https://img.shields.io/badge/-Daylight-e9e9e2?style=flat-square" alt="Daylight"> <img src="https://img.shields.io/badge/-Midnight-0c0e11?style=flat-square" alt="Midnight"> <img src="https://img.shields.io/badge/-Forest-143628?style=flat-square" alt="Forest"> <img src="https://img.shields.io/badge/-Savanna-a93c18?style=flat-square" alt="Savanna"> <img src="https://img.shields.io/badge/-Blueprint-12395f?style=flat-square" alt="Blueprint">

Open the picker from the Theme button, or press `T` anywhere to cycle through them. The new theme opens as a circle from where you click. The choice is saved in the browser and applied before the page paints.

IBM Plex Sans, Plex Sans Condensed and Plex Mono are embedded in each file, so the pages look the same offline.

| Page | Where the theme is saved |
|------|--------------------------|
| `social-login-test.html` | `premisaveSocialTestTheme` in local storage |
| `developer-console.html` | `dc-theme` in local storage |

## ![][i-send] Using the tokens in Postman

The collection has one request per provider in the "00 Social sign in" folder. Paste the token into the collection variable `google_id_token`, `facebook_access_token` or `github_code`, then send the request.

## ![][i-wrench] When something is off

| What you see | Likely cause |
|--------------|--------------|
| <img src="https://img.shields.io/badge/-Backend%20not%20reachable-b3261e?style=flat-square" alt="Backend not reachable"> | The service is not running, the base URL is wrong, or `DEVELOPER_PORTAL_ORIGINS` does not include `http://localhost:3000`. |
| <img src="https://img.shields.io/badge/-Not%20switched%20on%20in%20the%20service-8a5a00?style=flat-square" alt="Not switched on in the service"> | A provider button is disabled because its client ID, or for Facebook and GitHub its secret, is missing in the service `.env`. |
| <img src="https://img.shields.io/badge/-Could%20not%20load%20the%20SDK-8a5a00?style=flat-square" alt="Could not load the SDK"> | "Could not load Google sign in" or "Could not load the Facebook SDK": no internet access to the provider, or a content blocker. Facebook can still be used by pasting a token. |
| <img src="https://img.shields.io/badge/-GitHub%20error%20page-b3261e?style=flat-square&logo=github&logoColor=white" alt="GitHub error page"> | The callback URL on the OAuth App does not match the page address exactly. |
| <img src="https://img.shields.io/badge/-ACCOUNT__PENDING-8a5a00?style=flat-square" alt="ACCOUNT_PENDING"> | Confirm the email first, then sign in again. |
| <img src="https://img.shields.io/badge/-ACCOUNT__EXISTS-b3261e?style=flat-square" alt="ACCOUNT_EXISTS"> | The provider could not vouch for an address that already has an account. Sign in the usual way. |

[i-layers]: https://img.shields.io/badge/-%20-0b7a43?style=flat-square&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IiNmZmZmZmYiIHN0cm9rZS13aWR0aD0iMiIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cG9seWdvbiBwb2ludHM9IjEyIDIgMiA3IDEyIDEyIDIyIDcgMTIgMiIvPjxwb2x5bGluZSBwb2ludHM9IjIgMTcgMTIgMjIgMjIgMTciLz48cG9seWxpbmUgcG9pbnRzPSIyIDEyIDEyIDE3IDIyIDEyIi8%2BPC9zdmc%2BCg%3D%3D
[i-play]: https://img.shields.io/badge/-%20-0b7a43?style=flat-square&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IiNmZmZmZmYiIHN0cm9rZS13aWR0aD0iMiIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cG9seWdvbiBwb2ludHM9IjYgMyAyMCAxMiA2IDIxIDYgMyIvPjwvc3ZnPgo%3D
[i-sliders]: https://img.shields.io/badge/-%20-0b7a43?style=flat-square&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IiNmZmZmZmYiIHN0cm9rZS13aWR0aD0iMiIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cGF0aCBkPSJNNCAyMXYtN000IDEwVjNNMTIgMjF2LTlNMTIgOFYzTTIwIDIxdi01TTIwIDEyVjNNMSAxNGg2TTkgOGg2TTE3IDE2aDYiLz48L3N2Zz4K
[i-file]: https://img.shields.io/badge/-%20-0b7a43?style=flat-square&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IiNmZmZmZmYiIHN0cm9rZS13aWR0aD0iMiIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cGF0aCBkPSJNMTQgMkg2YTIgMiAwIDAgMC0yIDJ2MTZhMiAyIDAgMCAwIDIgMmgxMmEyIDIgMCAwIDAgMi0yVjh6Ii8%2BPHBvbHlsaW5lIHBvaW50cz0iMTQgMiAxNCA4IDIwIDgiLz48cGF0aCBkPSJNMTYgMTNIOE0xNiAxN0g4TTEwIDlIOCIvPjwvc3ZnPgo%3D
[i-toggle]: https://img.shields.io/badge/-%20-0b7a43?style=flat-square&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IiNmZmZmZmYiIHN0cm9rZS13aWR0aD0iMiIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cmVjdCB4PSIxIiB5PSI1IiB3aWR0aD0iMjIiIGhlaWdodD0iMTQiIHJ4PSI3Ii8%2BPGNpcmNsZSBjeD0iMTYiIGN5PSIxMiIgcj0iMyIvPjwvc3ZnPgo%3D
[i-activity]: https://img.shields.io/badge/-%20-0b7a43?style=flat-square&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IiNmZmZmZmYiIHN0cm9rZS13aWR0aD0iMiIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cG9seWxpbmUgcG9pbnRzPSIyMiAxMiAxOCAxMiAxNSAyMSA5IDMgNiAxMiAyIDEyIi8%2BPC9zdmc%2BCg%3D%3D
[i-palette]: https://img.shields.io/badge/-%20-0b7a43?style=flat-square&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IiNmZmZmZmYiIHN0cm9rZS13aWR0aD0iMiIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cGF0aCBkPSJNMTIgM2E5IDkgMCAxIDAgMCAxOGMxLjUgMCAyLTEgMS41LTJzMC0yIDEuNS0yaDJhNCA0IDAgMCAwIDQtNGMwLTUtNC41LTEwLTktMTB6Ii8%2BPGNpcmNsZSBjeD0iNy41IiBjeT0iMTEiIHI9IjEiLz48Y2lyY2xlIGN4PSIxMCIgY3k9IjciIHI9IjEiLz48Y2lyY2xlIGN4PSIxNSIgY3k9IjciIHI9IjEiLz48L3N2Zz4K
[i-send]: https://img.shields.io/badge/-%20-0b7a43?style=flat-square&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IiNmZmZmZmYiIHN0cm9rZS13aWR0aD0iMiIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cGF0aCBkPSJNMjIgMiAxMSAxMyIvPjxwYXRoIGQ9Ik0yMiAybC03IDIwLTQtOS05LTQgMjAtN3oiLz48L3N2Zz4K
[i-wrench]: https://img.shields.io/badge/-%20-0b7a43?style=flat-square&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IiNmZmZmZmYiIHN0cm9rZS13aWR0aD0iMiIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cGF0aCBkPSJNMTQuNyA2LjNhMSAxIDAgMCAwIDAgMS40bDEuNiAxLjZhMSAxIDAgMCAwIDEuNCAwbDMuNzctMy43N2E2IDYgMCAwIDEtNy45NCA3Ljk0bC02LjkxIDYuOTFhMi4xMiAyLjEyIDAgMCAxLTMtM2w2LjkxLTYuOTFhNiA2IDAgMCAxIDcuOTQtNy45NGwtMy43NiAzLjc2eiIvPjwvc3ZnPgo%3D