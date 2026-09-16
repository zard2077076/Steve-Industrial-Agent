# Contributing

Discuss changes when shared ownership or scope is unclear; an already-authorized local fix
does not require opening an Issue or requesting approval again. Never submit saves,
worlds, logs with private paths, accounts, tokens or third-party mod JARs.

Use Java 17 and keep `core` and `adapter-api` loader-neutral. Add meaningful regression
coverage using the existing framework; test-first is useful, not a required ritual.
Run relevant Gradle tests/build and isolated physical checks when execution changes.
Use native shell wrappers on macOS, PowerShell counterparts on Windows. Formal/important worlds are never test
fixtures. Pull requests must describe safety impact, exact commands/results and
the compatibility profile used.
