---
description: Prepare a new release entry in the AppStream metainfo from recent git changes
disable-model-invocation: true
---

# /release — prepare a new release entry

Cuts the release-notes side of a new release. Releases are driven by git tags
(`v<X>.<Y>.<Z>`); the actual tag, build, and publish happen elsewhere — this
skill only edits the AppStream metainfo so the release notes are ready to ship.

The release notes file:
`src/main/resources/app/io.github.subsoundorg.Subsound.metainfo.xml`

Each `<release>` entry uses `version="@version@"` as a placeholder that the CI
build job substitutes from the git tag. The next CI run after a tag fills in
the version when the artifact is built.

## Inputs (run before editing)

- Latest tag (most recent `v*` tag, sorted by version):
  !`git tag --list 'v*' --sort=-version:refname | head -n 1`
- Today's date (ISO):
  !`date +%Y-%m-%d`

Strip the leading `v` from the tag → `LAST_TAG_VERSION` (e.g. `v0.6.20` → `0.6.20`).

Then use the Bash tool to fetch the commit list (substitute the actual tag value for `LAST_TAG`):

```
git log LAST_TAG..HEAD --pretty=format:'%h %s' --no-merges
```

## Procedure

1. **Backfill the previous release's version placeholder.** Open the metainfo
   file. Look at the topmost `<release>` element inside `<releases>`.
   - If its `version` attribute is still `@version@`, that means the previously
     tagged release never got its placeholder substituted in source. Replace it
     with `LAST_TAG_VERSION`. Leave the `date` and `<description>` body alone.
   - If it already has a real version string, do nothing to it.

2. **Inventory user-visible changes since `LAST_TAG`.** Read the commit list
   from above. For each commit, decide whether the change is observable to the
   end user. Discard:
   - Refactors and code-style changes.
   - Test-only changes.
   - Internal performance work that doesn't change perceived speed.
   - Build/CI tweaks.
   - Doc-only edits.

   When a commit message is terse, read the diff (`git show <hash>`) before
   classifying it.

   **When in doubt, ask — don't guess.** If, after reading the diff, it's still
   unclear whether a change belongs in the release notes, ask the user with the
   AskUserQuestion tool (include / leave out). Batch the uncertain ones into a
   single round of questions rather than asking one at a time.

3. **Insert a new `<release>` element as the first child of `<releases>`,
   above all existing entries.** Shape:

   ```xml
   <release version="@version@" date="YYYY-MM-DD">
     <description>
       <ul>
         <li>New: ...</li>
         <li>Improved: ...</li>
         <li>Bugfix: ...</li>
       </ul>
     </description>
   </release>
   ```

   - `date` is today (from the `!` block above).
   - `version` stays as the literal `@version@` placeholder — CI fills it.
   - One `<li>` per user-visible change. Prefix each with one of:
     - `New:` for a brand-new feature.
     - `Improved:` for an enhancement to existing behavior.
     - `Bugfix:` for a fix.
   - Order within the list: `New` first, then `Improved`, then `Bugfix`.
   - Tone: present tense, one user benefit per line, plain language. Match the
     two most recent existing entries in the file — skim them first to
     calibrate. Don't reference internal class/file names.
   - If a change is clearly worth including but you're unsure how to describe
     it to an end user (which prefix, or what the user-facing benefit actually
     is), ask the user with AskUserQuestion how they'd like it worded — offer
     two or three concrete candidate `<li>` lines as options. Better to ask
     than to ship vague or wrong wording.
   - If there are zero user-visible changes since `LAST_TAG`, stop and tell
     the user — don't fabricate notes.

4. **Show the diff.** Do not commit, push, tag, or run any other git
   write-operations. Those are explicit follow-up steps the user will run.

## What this skill does NOT do

- Does not create or push a tag.
- Does not edit `build.gradle` or any version constant.
- Does not run the build.

If the user asks for any of those, do them as separate explicit actions after
they review the metainfo edit.
