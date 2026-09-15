# Contributing

Thanks for your interest in Mockonomy! Issues and pull requests are welcome.

## Before you start

- For anything larger than a small fix, please open an issue first so we can discuss the
  change.
- Keep pull requests focused: one topic per pull request.

## Development setup

- JDK 21 (for example the JBR bundled with Android Studio) and the Android SDK
- `./gradlew build` runs the unit tests of every module, Android Lint and the R8 release
  build — the same scope as CI
- `:engine` tests run on the plain JVM in milliseconds; no emulator is needed
- `./gradlew :app:installDebug` installs the app on a connected device

## Ground rules

- **Spec first.** Engine behavior is defined in [`spec/`](spec/README.md). If you change how
  matching, rules, templating, sequential responses or the state machine behave, update the
  relevant `spec/behavior/*.md` document and add or update a fixture under
  `spec/fixtures/` in the same change. Make sure a new fixture really fails when you break
  the behavior it covers.
- **`:engine` stays pure Kotlin/JVM.** No `android.*`; avoid `java.time` and `java.io` —
  use `kotlinx-datetime`, `okio` and `kotlinx.serialization` instead.
- **Loopback only.** The mock server must only bind to `127.0.0.1`.
- **Safe defaults.** New security-relevant settings default to the safe side (for example:
  automation API off, header masking on).
- **English everywhere** — code, comments, test names, docs and commit messages.
- **Test names describe behavior**, written with backticks:
  ``fun `trailing slash is meaningless`()``.
- UI changes should be checked on a real device as well; unit tests passing is not the same
  as the flow working on a phone.

## Reporting a security issue

Please do not open a public issue for security problems. Use GitHub's private vulnerability
reporting instead (the repository's **Security** tab → **Report a vulnerability**).
