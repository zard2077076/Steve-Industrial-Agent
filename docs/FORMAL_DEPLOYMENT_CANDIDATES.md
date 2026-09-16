# Formal deployment candidate report

Status: **FB-08 complete, all candidates pending**. This is a review inventory only.

The accepted FS-12 survey produced eight deterministic machine-seeded candidate zones. FB-07 rebound all eight to the completed backup and produced eight candidate packages. Every package has the same conservative state:

- selection: `PENDING_USER_SELECTION`;
- claim permission: `UNKNOWN`;
- preview: unavailable;
- verified physical plan: unavailable;
- risk assessment and material/power/mutation budget: absent because their prerequisite physical evidence is absent;
- required authorizations: explicit user selection, verified clear space/player-building boundary, claim/region authorization, human approval and a separately authorized formal execution stage;
- execution: `FORMAL_WORLD_EXECUTION_FORBIDDEN`.

Ranking is retained only to make human review deterministic. It does not select the first row, recommend a site or imply that unscanned/partially scanned space is safe. The 16-region/9,733-chunk survey remains bounded partial evidence; malformed or bounded-away data remains unknown.

The candidate count and status are recorded in `work/formal-backup-acceptance/fb07-20260720-141225/acceptance.json`. Exact candidate geometry remains in ignored survey/acceptance evidence rather than Git documentation. No player, container, inventory or private-save semantics are included.

The next valid transition, if the user chooses to continue, is an explicit selection of one registered candidate followed by new read-only evidence gathering. Selection alone cannot create an approval scope while preview, physical, risk, budget, permission and boundary evidence remain unavailable.
