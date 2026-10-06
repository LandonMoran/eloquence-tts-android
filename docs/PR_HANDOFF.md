# Prepared issue: consolidated TTS branch handoff

This is a ready-to-post issue body, saved here because this coding environment blocks GitHub issue and PR creation. The branch link below points to the current revision; the pinned commit identifies the first follow-up fix. Copy the body below into a repository issue, then use its embedded PR description.

## Branch and current status

- Repository: [LandonMoran/eloquence-tts-android](https://github.com/LandonMoran/eloquence-tts-android)
- Branch: `coderabbit/fix-open-issues-engine/5b367011`
- First published commit from this follow-up audit: [`a3e91f00fd7e42768c6d8dd855d25c818c524fa3`](https://github.com/LandonMoran/eloquence-tts-android/commit/a3e91f00fd7e42768c6d8dd855d25c818c524fa3)
- Base: `main`
- [Browse branch](https://github.com/LandonMoran/eloquence-tts-android/tree/coderabbit/fix-open-issues-engine/5b367011) · [Open comparison / create PR](https://github.com/LandonMoran/eloquence-tts-android/compare/main...coderabbit/fix-open-issues-engine/5b367011?expand=1)
- PRs #240, #241, #286 and #287 have been reviewed and integrated into this branch's history. They have not been merged into `main` by this task.
- The coding environment permits branch pushes but refuses both issue and PR creation with `CODERABBIT_AGENT_RUNTIME_OWNS_GIT_DELIVERY`. This document is the prepared issue body for creating one consolidated PR; the issue command was also rejected with exit 89.

## What the branch changes

Native session lifetime/cancellation/error handling; PCM boundaries; Direct Boot settings and atomic mirroring; dictionary bounds/decoding/cache; shared voice capability contracts; update flow lifetime/version/install checks; generated assets; CI/signing/oracle workflows; removal of demonstrably unused code. Full mapping: [repository-audit.md](https://github.com/LandonMoran/eloquence-tts-android/blob/coderabbit/fix-open-issues-engine/5b367011/docs/repository-audit.md).

The latest pass confirmed and fixed four more defects:

1. Locked-device preference edits could revert to the older credential copy after unlock.
2. ECI clear/add/start errors could leave failed native sessions eligible for reuse.
3. Embedded carriage returns could corrupt line-based stored dictionary entries.
4. CR-only dictionary files merged records; the bounded importer now handles LF, CRLF and CR with all supported BOM encodings.

## Validation and confidence

- Passed locally on the branch: full Kotlin compilation against the public Android API 34 jar, seven host regression groups, JNI/oracle/compatibility ASan+UBSan suites, and eight contract tests.
- One new failure-injection test initially caught an unfixed synthesis-start branch; that branch was fixed and the final native suite passed.
- Previous generated-asset and workflow checks are reused only where relevant inputs stayed unchanged.
- [Branch CI runs](https://github.com/LandonMoran/eloquence-tts-android/actions?query=branch%3Acoderabbit%2Ffix-open-issues-engine%2F5b367011) must be checked for the PR's exact head. Earlier failures are documented and corrected; a running build is not a pass.
- Still required: real-device listening, rapid TalkBack stop/restart, reboot before first unlock, service-rebind stress and an actual signed update confirmation flow.
- Closed issue #141: the historical signing key is absent from the current tree, but remains in Git history. Owner confirmation of key rotation and released APK identity is still needed. Configure protected `release` environment reviewers/tag restrictions before release signing.

**Assessment:** the reproduced findings in the covered host/native paths are fixed and their regressions pass. This audit continues to find defects, so I am not claiming the repository is defect-free or that its bugs are mostly gone. File/issue/commit inventories are coverage records, not proof of every file's semantics or every historical acceptance criterion. The 45 original open issues have implementation mappings; they have not been administratively closed by this task.

## How to create the PR

1. Open the comparison link above.
2. Set base repository to this repository and base branch to `main`; compare branch must be `coderabbit/fix-open-issues-engine/5b367011`.
3. Use title **Consolidate TTS fixes, remove dead code, and repair audited regressions**.
4. Paste the PR description below. Create as a draft until CI for its head is green and the listed acceptance gaps are reviewed.
5. Put the resulting PR link in this issue. Merge only after review; use a merge commit if the integrated PR ancestry should remain visible. Close superseded PRs after the consolidated PR lands, and close this handoff issue then.

Alternatively, from an authenticated checkout with permission to create PRs:

```bash
gh pr create --repo LandonMoran/eloquence-tts-android \
  --base main --head coderabbit/fix-open-issues-engine/5b367011 --draft \
  --title "Consolidate TTS fixes, remove dead code, and repair audited regressions" \
  --body-file pr-description.md
```

Save only the description below to `pr-description.md` before running that command.

## Ready-to-use PR description

```markdown
## Summary

The latest adversarial pass also preserves Direct Boot settings edits across unlock, retires native sessions after clear/add/start failures, prevents carriage returns from corrupting stored dictionary records, and preserves CR-only imported records.

Consolidate reviewed PRs #240, #241, #286 and #287 with the application/runtime fixes on this branch. Optimization is limited to removing unused code and state; confirmed correctness gaps are repaired separately.

- Fix native cancellation, ownership, configuration failure handling, PCM endpoints and bounded allocations. Closed issue #43 still used raw pointer handles: replace them with registry IDs/reference-held lifetimes and safe deferred shutdown.
- Fix Direct Boot preferences, dictionary bounds/cache/strict decoding, voice registry/contracts, pitch mapping, update ownership/version/install contracts and synthesis work budget.
- Fix closed warmup issue #187 and corpus issues #99/#52/#69 with deterministic regressions.
- Remove unused futures, wrapper instance/state, obsolete locale helpers and mutable fields with no remaining use.
- Include isolated release signing, exact APK ABI checks, pinned oracle inputs, explicit fanout assembly and content-aware asset generation from reviewed PR #287. Fix its observed CI failure caused by relying on ripgrep on GitHub runners.

## Review and integration

All four PR heads are ancestors of this branch. The fixes from #240/#241/#286 were already incorporated and tested; merge records retain that implementation. #240's tail fade bug was corrected. #287's older copies of tests/tools were resolved in favor of the current regression fixes. Do not merge the standalone workflow PR before its application dependencies.

## Audit scope

Inventoried every current file, all 225 issues (45 open/180 closed), and all 570 commits reachable before consolidation. The coding task includes file hashes, categories, issue closure links and a commit ledger. This does not mean every historical revision was rebuilt or every vendored/reference file was semantically proven correct. See `docs/repository-audit.md` for verified findings and acceptance gaps.

## Validation

- Passed: full Kotlin compile and seven host regression groups; ASan/UBSan JNI/oracle/compatibility tests; source/resource/archive format checks; workflow lint; generated asset comparison (unchanged inputs reused).
- Added native stale/invalid/repeated-handle and concurrent-shutdown tests, queued/in-flight warmup teardown tests, Unicode/case/locale corpus tests, corrupt dictionary and duplicate APK-entry tests.
- Consolidated contracts include a tracked-signing-key guard. Android CI status is tracked on this branch; do not infer device readiness from host fixtures.
- Outstanding: actual device listening, reboot before first unlock, service-rebind stress, signed installer interaction, and owner confirmation that the historical compromised signing key was rotated. The key remains in Git history; this PR does not rewrite shared history or rotate production identity.

## Issues implemented

Fixes #239
Fixes #242
Fixes #243
Fixes #244
Fixes #245
Fixes #246
Fixes #247
Fixes #248
Fixes #249
Fixes #250
Fixes #251
Fixes #252
Fixes #253
Fixes #254
Fixes #255
Fixes #256
Fixes #257
Fixes #258
Fixes #259
Fixes #260
Fixes #261
Fixes #262
Fixes #263
Fixes #264
Fixes #265
Fixes #266
Fixes #267
Fixes #268
Fixes #269
Fixes #270
Fixes #271
Fixes #272
Fixes #273
Fixes #274
Fixes #275
Fixes #276
Fixes #277
Fixes #278
Fixes #279
Fixes #280
Fixes #281
Fixes #282
Fixes #283
Fixes #284
Fixes #285
```

This handoff records the completed follow-up audit. The additional line-ending regression passes alongside the other host groups; no claim of exhaustive correctness is made.
