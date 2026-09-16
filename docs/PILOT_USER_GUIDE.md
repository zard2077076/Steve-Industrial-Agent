# Pilot user guide

Use only the independent disposable client described by `INSTALLATION.md`.
Do not launch an important instance and do not copy this JAR into one.

## First use

```text
/industrialagent version
/industrialagent compatibility
/industrialagent setup show-config
/industrialagent setup mark-test-world
/industrialagent setup validate
/industrialagent backup prepare
```

Save and fully close the game, run the bundled backup helper, reopen the same
world and require `/industrialagent backup verify` PASS.

## Gravel and iron-sheet acceptance

```text
/industrialagent pilot region here 32 32 20
/industrialagent pilot region preview
/industrialagent pilot region confirm
/industrialagent pilot readiness minecraft:gravel 3
/industrialagent pilot dry-run minecraft:gravel 3
/industrialagent pilot start minecraft:gravel 3
/industrialagent pilot status
/industrialagent pilot cleanup preview
/industrialagent pilot cleanup
/industrialagent pilot readiness create:iron_sheet 2
/industrialagent pilot dry-run create:iron_sheet 2
/industrialagent pilot start create:iron_sheet 2
/industrialagent pilot status
```

Preview and dry-run must say zero world mutation. Start shows a three-second
countdown and visible BUILD, CONNECT, FEED, PROCESS and VERIFY stages. Gravel
must report required/observed 3/3; iron sheet must report 2/2. Cleanup preview
must report only journal-owned cells and unknown removals must remain zero.

## Cancel and reload

For cancel, run a start and execute `/industrialagent pilot cancel` during its
countdown or safe phase, then inspect status and cleanup preview.

For reload:

```text
/industrialagent pilot hold build
/industrialagent pilot start create:iron_sheet 2
```

Wait for `HOLD reached phase=BUILD persisted=true`, Save and Quit to Title,
re-enter the same world, then run:

```text
/industrialagent pilot recovery-status
/industrialagent pilot resume
/industrialagent pilot status
/industrialagent pilot cleanup preview
/industrialagent pilot cleanup
```

Success includes exact rescan plus `duplicatePlacement=false`,
`duplicateConsumption=false` and exact output. PROCESS may conservatively
refuse. If anything fails, stop and run `/industrialagent export-diagnostics`;
review the ZIP before sharing and never attach a save, playerdata, account file,
chat log or third-party modpack JAR.
