# phone-agent

Sideloaded Android app that runs task packs through an accessibility service. Design: `docs/ARCHITECTURE.md` (decision log at the end). Single-person project: no specs; record hard-to-reverse decisions there.

## Build and test

- Gradle 8.11 needs JDK 17–21. Android Studio's bundled JBR is 25 and fails; use `~/.jdks/jbr-21.0.11`.
- `export JAVA_HOME="$(cygpath -w ~/.jdks/jbr-21.0.11)" && ./gradlew :core:test :app:assembleDebug`
- `core` is pure Kotlin: put logic there and test it on the PC with scripted frames (`CoreTest.kt`).

## Invariants (do not break without the owner's explicit decision)

- No network: the manifest has no INTERNET permission. A future "check for updates" connects only on user tap, to one fixed host, and verifies pack signatures.
- Only act on apps listed in the pack's `requires`; anything else in front stops the run (`Outcome.WRONG_APP`).
- Screen trees, screenshots and OCR text stay in memory for one round. Traces hold structure and timings only.
- The action set has no text input and no sending. New actions need a safety review.
- Packs are data only. Finders, expectations and actions are registered by name in `Builtins.registry`; unknown names are rejected by `validate`. No runtime code plugins or script engines.

## Conventions

- Validate at trust boundaries (pack loading, platform data); inside, rely on types.
- Name modules and files by concept. No spec or ticket IDs in code.
- A new finder, expectation or action is registered in `Builtins` and gets a test in the same change.
- Waiting is event-driven (`awaitSettle`), never a fixed sleep.

## Privacy

- Never commit `tools/probe/out/` or any unredacted UI tree or screenshot: they contain real chats. Redact names and message text before adding a fixture.
- Test only with secondary accounts.
