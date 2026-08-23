---
status: accepted
---

# The shell is a verbatim prototype port; roles emphasize order, never visibility

The first shell implementation bent AntD's Menu/Layout toward the prototype and the user
rejected the result as generic-SaaS. The user then explicitly authorized overturning the
design ("你可以直接推翻当前的设计"). This ADR records the replacement:

1. **The prototype's own shell markup and CSS are the specification.** The sidebar
   (brand, workspace switcher, search entry, grouped flat nav with glyphs and badges,
   shared-identity footer) is ported 1:1 into hand-written CSS (`shell.css`, `zs-`
   prefixed). AntD remains the component system for page content only. Colors in
   `shell.css` are exclusively `--zs-*` variables injected from `saasTheme.ts` — the
   single color source is preserved literally, not by convention.

2. **Roles reorder the rail, never filter it.** Selecting a role permutes navigation
   *section order* (each role's daily line rises to the top) and sets the default
   landing page. Every section stays visible for every role, with an explicit nav note
   saying so. This honors D1 (role ≠ identity/permission) while restoring the
   prototype's per-role feel that a uniform static tree lacked. The rail is a pure
   presentation layer (`shellRail.ts`); `appNavigation` remains the single data source
   and is unchanged.

3. **Badges show real counts or nothing.** The review-inbox badge is the OPEN count for
   the role's `responsible_team` (`size=1`, `total_elements` — never the page-crawling
   anti-pattern). No role selected → no requests. Finance has no team (D4) → no badge.
   Fetch failure → no badge. Numbers are never invented (ADR 0001).

4. **Global search ships as an honest entry, superseding #104's blanket exclusion.**
   The ticket excluded the prototype's fake cross-object search. Instead of dropping
   the entry, it opens an overlay that states cross-object search is not yet backed by
   an endpoint and routes Enter to the real `/orders?query=` search. When a global
   search endpoint exists, the overlay upgrades in place.

5. **The dispatch console renders inside the my-workbench rail group** (presentation
   only; it stays a top-level node in `appNavigation`, so admission counts and
   navigation context are unaffected).

## Considered options

- Keep the AntD-token approximation: rejected by the user as "差点意思" — the Menu
  component cannot reproduce the prototype's exact density, glyphs, and badge language.
- Per-role rails that hide non-role sections (the prototype's literal RAILS behavior):
  rejected — it would visually imply a permission system that Phase 1 does not have,
  violating D1 and #104's "menus must not hide or lock by role".
- A second bespoke design system: rejected — one CSS file scoped to the shell, colors
  injected from the existing theme, zero new dependencies.
