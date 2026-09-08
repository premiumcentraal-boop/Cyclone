# 4.2.1 source checkpoints

Base v4.2.0: 011de009ff6871be64a3df1e63308a3ac027e282. Sprint branch codex/cyclone-4.2.1-task-composer. Release identity 4.2.1 / 82.

2839f64: bounded pending requests and task-bound attachments.
6f4ed54: separate overlay current-task controls, Up next and new composer.
0fe5850: independent in-app Chat and explicit Phone task mode.
ad78118: targeted UI revisions, cross-plane start guards and regression updates.
34c8588: narrow app-picker compile fix.
6c85ec6: keyboard compaction and additional separation/queue tests.

The release commit adds authorization, release notes and teardown of any unclaimed attachment. Refer to release/source-sha and run-id sidecars for final provenance. No phone verification. Up next is explicitly session-only and user-started, not an additional execution plane.
