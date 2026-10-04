# Nuvio Enhanced TV

Independent, unofficial native fork of NuvioMedia/NuvioTV. No endorsement by
NuvioMedia is claimed. Upstream copyright, GPL-3.0 license and third-party notices
remain applicable. The upstream documentation is preserved in Git history.

Project specification, audit, baseline results and milestone status:
https://github.com/Pepeu2010/nuvio-enhanced

Baseline: `a4c3094509bd9c4bd8a147ccf3c9f3614ba6a2fe` (upstream dev).
Foundation changes dated 2026-10-04: independent application IDs/labels, own update
repository, debug signing without an upstream keystore, explicit release signing
configuration, crash reports off by default, and sensitive addon diagnostic URL
and Sentry text/breadcrumb redaction. Kotlin/JNI namespaces remain stable.

Baseline APKs generated. Baseline full suite: 1839 tests, 20 failed, 1 skipped.
These inherited failures remain tracked; this fork is not yet a validated product
release. Real login/sync/playback and physical TV Box QA remain pending.
