---
name: spike
description: Run a time-boxed technical spike from phase-01-spikes.md to de-risk a decision with measurements on the tablet, then record evidence and the decision in decisions.md.
---
# spike
1. Read the spike block (question, options, build, measurements, decision rule) and the referenced ADR in `docs/architecture/decisions.md`.
2. Build the minimum code. Spike screens live in `app/src/debug/kotlin/dev/folio/app/spikes/` behind debug routes `spike-*`. Reusable parts go directly into their target module with tests.
3. Measure exactly the listed metrics, same conditions for every option. Use PerfMonitor sections, `gfxinfo.sh`, `diag.sh`, and `debugcmd.sh perf-dump`. Run each measurement at least 3 times; report median and worst.
4. Record in decisions.md under the ADR: `Evidence YYYY-MM-DD:` a small table of numbers per option, then `Decision:` and `Status: Accepted`. Update the affected architecture doc sections.
5. No option meets the rule: pick the best, record the gap, add an AMENDMENTS entry listing affected tasks, and add a USER-CHECK if the user must accept the trade-off.
6. Time box: about two sessions. If exceeded, record partial evidence, apply the ADR's default option, move on.
7. Physical-only measurements (feel, perceived latency, real pen tilt/buttons): prepare the screen and logging, then add a precise USER-CHECK. Continue with other spikes meanwhile.
8. P01-T08 deletes or promotes all spike code. Spike code must still pass `qa` while it exists.
