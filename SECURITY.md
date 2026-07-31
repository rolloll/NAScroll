# Security policy

## Scope

NAScroll connects directly to a Synology DSM File Station endpoint and stores credentials locally to support persistent login. Treat the application and its cache as personal-device software.

## Reporting a vulnerability

Do not open a public issue for credentials, session handling, TLS, authentication, or data exposure problems. Contact the repository owner privately with:

- A short description of the issue and impact
- Affected version, Android version, and DSM version
- Reproduction steps using synthetic data where possible
- Logs or screenshots with credentials, cookies, NAS addresses, and personal file names removed

If no private security contact is configured for the GitHub repository yet, create one before publishing the repository or temporarily keep the repository private.

## User precautions

- Use HTTPS and a least-privilege DSM account.
- Do not use a rooted or shared device for credentials that must remain confidential.
- Never paste passwords, cookies, or private NAS URLs into GitHub issues, pull requests, or build logs.
