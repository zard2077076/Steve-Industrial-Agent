# C-09 Compacting execution

Status: WIP automated runtime gate passed; final integrated acceptance pending.

## Supported bounded shape

- exact `create:compacting` recipe;
- one through nine counted ITEM inputs;
- zero through four exact FLUID inputs with a 64,000mB aggregate guard;
- one deterministic ITEM output;
- `BasinHeatMode.NONE`;
- reviewed ordinary-bucket material binding and one physical fluid execution;
- no fluid output;
- no optional byproduct or residue;
- fixed owned motor, mechanical press, Basin and output chest;
- exact live speed, stress, Basin contents, press-cycle and chest-output
  evidence.

The item-only reference plan uses the live `create:compacting/blaze_cake` recipe:
`create:cinder_flour`, `minecraft:sugar` and the resolved egg ingredient produce
`create:blaze_cake_base`. Runtime recipe identity and ingredient matching remain
authoritative; the example does not permit free-text or unknown recipe
execution.

The fluid reference plan uses `create:compacting/granite_from_flint`: two
flint, one red sand and exactly 100mB lava produce one granite. Its material
bill reserves and consumes one whole `minecraft:lava_bucket`; quantity two is
refused until multi-cycle fluid execution has separate physical evidence.

## Handler boundary

`Create606BasinPressActionHandler` places one journal-owned component per BUILD
invocation. POWER reads the live Create kinetic network. FEED requires an empty
Basin/output chest, resolves the exact live compacting recipe, rejects non-NONE
heat, extracts every exact input and reviewed fluid bucket from the shared
resource buffer, then pours the declared mB into the real Basin before
`BasinRecipe.match`. It records one injected-resource journal entry per ITEM and
FLUID identity. PROCESS observes the live mechanical-press behavior and
transfers only actual Basin output inventory to the planned chest.

Failure cleanup drains only the exact injected fluid before returning its
bucket. If exact drain fails, the handler does not recreate the bucket. This
prevents a recovery path from duplicating fluid.

The handler does not call `BasinRecipe.apply`, `applyProcessing`, `process` or
another recipe-output method. It does not create the expected output stack, use
a fixed sleep, teleport a Bot, access player inventory or attach to an arbitrary
existing factory.

## Typed refusals

- HEATED or SUPERHEATED recipe;
- fluid output, unreviewed fluid/container or multi-cycle fluid execution;
- chocolate/honey input until an exact item-form container binding is reviewed;
- probabilistic or additional output;
- residue or unknown NBT behavior;
- wrong live recipe/type/ingredient multiset;
- changed geometry, obstruction, foreign Basin/chest contents;
- missing/overstressed/wrong kinetic state;
- formal-world execution;
- resource-bearing reload without exact compensation evidence.

## Current automated evidence

The shared C-06-through-C-10 suite proves exact multi-stack
Direct/Bots/Hybrid equivalence, the real item-only blaze-cake process and the
real 100mB-lava granite process. The lava gate proves the same
VerifiedPhysicalPlan, unique output, bucket consumption, final empty Basin,
journal, reload and cleanup in all three modes; pre-feed cancellation also
returns the exact lava bucket in all three modes. The aggregate gate passes 63/63
in `work/logs/goal-driven-execution-gametest-20260810-124736.log`; the catalog
survey maps all three reviewed lava recipes and keeps fluid Composite stages
typed unsupported in `work/logs/derivable-product-survey-20260810-121857.log`.
The unchanged Composite settlement/recovery boundary passes 12/12 plus separate
resume and reload/cancel processes in
`work/logs/composite-player-order-20260810-123837.log`,
`work/logs/composite-player-order-resume-20260810-125034.log` and
`work/logs/composite-player-order-reload-20260810-125159.log`. Unattended and
restart warehouse gates pass in
`work/logs/warehouse-unattended-20260810-125308.log` and
`work/logs/warehouse-restart-20260810-125807.log`. The final clean build passes
all 23 tasks and 850 XML-reported tests with zero failures/errors in
`work/logs/macos-clean-build-lava-compacting-final.log`.

C-09 is not complete: mid-feed resource-bearing recovery, multi-cycle fluid
settlement and final player-visible acceptance remain. No independent PCL2
visible run is permitted.
