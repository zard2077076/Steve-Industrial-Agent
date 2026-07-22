# Contributing

Open an issue before changing execution or safety contracts. Never submit saves,
worlds, private logs, absolute local paths, account data, credentials, tokens or
third-party mod JARs.

Use Java 17. Keep `core` and `adapter-api` loader-neutral, add typed tests for
behavior changes, and run:

```powershell
.\gradlew.bat clean build
```

Formal or important player worlds must never be test fixtures. Pull requests
must describe their safety impact, exact test results and compatibility profile.
