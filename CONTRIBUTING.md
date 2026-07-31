# Contributing

NAScroll is currently a personal-use project. Small, focused fixes are welcome when they do not require sharing private NAS data or credentials.

Before opening a pull request:

1. Explain the user-visible problem and the affected reader or API path.
2. Include reproduction steps that use synthetic or publicly redistributable files.
3. Run `./gradlew :app:assembleDebug`.
4. Test on a real Android device when changing WebView, pagination, PDF, or gesture behavior.
5. Confirm that logs, screenshots, EPUBs, credentials, NAS URLs, keystores, and generated APKs are not included.

Do not submit passwords, session cookies, private NAS paths, copyrighted books, or other sensitive data in issues or pull requests. See `SECURITY.md` for vulnerability reports.
