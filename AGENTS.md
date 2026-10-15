# Project guidance

## Branch workflow

- Start each new plan or feature on a fresh, dedicated branch before making
  implementation changes. Use the `codex/` branch prefix by default.
- Implement the plan or feature, update affected documentation, and run
  appropriate validation on that branch.
- Once implementation and validation are complete, merge the branch into
  `main`. The user has authorized this workflow; no separate merge approval
  is needed unless a specific task requires it.
- Preserve unrelated uncommitted work and keep it out of the feature's
  commits and merge.

## Keep the design current

- For every change, check whether `docs/DESIGN.md` needs an update.
- Update the affected design sections in the same change whenever behavior,
  user flows, architecture, APIs, data models, security, platform constraints,
  or deployment assumptions change.
- Keep the design consistent with the resulting implementation. For example,
  document a changed API contract or permission flow alongside its code change.
- Leave the design unchanged when the change does not affect the documented
  design, such as a refactor that preserves behavior or a typo fix.
