# Accessibility — posly

## Scope

Covers WCAG 2.1 AA compliance (keyboard operability, screen reader support) for the POS Compose
Multiplatform UI (`app/sharedUI` — Android, iOS, Desktop). **There is no browser-renderable POS
UI in this codebase** — the one `js`/web target that exists (`app/webApp`) is an unused JetBrains
wizard template stub with none of the actual product screens, so this document does not (and
cannot meaningfully) cover browser-based WCAG tooling (axe-core, Lighthouse accessibility audits,
etc.). See `PERFORMANCE.md` for the same caveat on the performance side of this same ticket.

## What Compose already gives you for free

Every interactive element in this app is a standard Material3 composable (`Button`, `TextButton`,
`OutlinedTextField`, `Checkbox`, `RadioButton`, `OutlinedButton`) rather than a custom
non-focusable view. Standard composables are keyboard-focusable and Tab-traversable by default on
Desktop, and screen-reader-navigable by default on Android (TalkBack) and iOS (VoiceOver) — this
was true before this audit and required no work. `TextField`'s `label` is exposed as the
control's accessible name automatically; a `Button`/`TextButton`'s child `Text` is exposed as its
accessible name automatically. The app also uses **zero icon-only buttons anywhere** in
`commonMain` — every action is a text-labeled button, which sidesteps the most common
"unlabeled icon button" accessibility bug before it can occur.

## What this audit found and fixed

1. **Custom clickable rows had no accessible role or action label.** A `Card` or `Row` wrapped in
   `Modifier.clickable { ... }` (used for search-result rows, dashboard drill-down cards, and
   admin list-item navigation) is tappable, but without an explicit `role` a screen reader
   announces it ambiguously (not as "button"), and without `onClickLabel` it announces no action
   description beyond "double tap." Fixed in:
   - `pos/SaleScreen.kt` (search suggestion rows — `role = Role.Button`, `onClickLabel = "Add
     <product> to cart"`)
   - `pos/ManagerDashboardScreen.kt` (sales/transactions/top-product drill-down cards)
   - `admin/UserListScreen.kt`, `admin/TaxProfileListScreen.kt`, `admin/StoreListScreen.kt`
     (edit-navigation rows — `onClickLabel = "Edit <name>"`)

2. **A selectable option row didn't use Compose's canonical selectable-row pattern.**
   `pos/ProductDetailModal.kt`'s product-modifier option rows (e.g. "Size: Small/Medium/Large")
   had the outer `Row` on `.clickable` AND the inner `RadioButton` with its own `onClick` — two
   separate, redundant focus/announcement targets for what a screen reader should treat as one
   control. Fixed by moving to `Modifier.selectable(selected, role = Role.RadioButton, onClick)`
   on the row and setting the `RadioButton`'s own `onClick = null` (making it a pure visual
   indicator) — the documented Jetpack Compose pattern for this exact shape.

3. **Dynamic status/error/info messages weren't announced (WCAG 2.1 AA 4.1.3, Status
   Messages).** Text that appears after an action (login failure, checkout error, void
   confirmation, refund outcome, shift variance warning, etc.) rendered visually but wasn't
   marked so assistive tech announces it without the user navigating to find it. Added a shared
   `Modifier.statusMessage()` helper (`accessibility/Accessibility.kt`, `liveRegion =
   LiveRegionMode.Polite`) and applied it to every such message across `SaleScreen`,
   `LoginScreen`, `MfaScreen`, `ShiftScreen`, `RefundScreen`, `ManagerDashboardScreen`, and
   `TransactionListScreen`.

## Automated regression coverage

New/extended Compose UI tests assert the fixes above hold going forward (see
`accessibility/AccessibilityMatchers.kt` for the custom `hasRole`/`hasOnClickLabel`/
`hasLiveRegion` semantics matchers used):

- `pos/SaleScreenTest.kt` — search suggestion role/label, error-message live region
- `pos/ManagerDashboardScreenTest.kt` — drill-down card role/label (all three cards)
- `admin/UserListScreenTest.kt` — user row role/label

## Audit coverage — what was and wasn't deeply reviewed

**Deeply audited and fixed:** the checkout/POS-operational flow (`SaleScreen`, `LoginScreen`,
`MfaScreen`, `ShiftScreen`, `RefundScreen`, `ManagerDashboardScreen`, `TransactionListScreen`,
`ProductDetailModal`) plus the clickable-row pattern wherever it appears elsewhere
(`UserListScreen`, `TaxProfileListScreen`, `StoreListScreen`).

**Not deeply audited:** the remaining admin CRUD screens (`StoreFormScreen`,
`TaxProfileFormScreen`, `UserFormScreen`, `FeatureFlagFormScreen`/`FeatureFlagListScreen`,
`ImportWizardScreen`, `SsoConfigScreen`, `DeviceListScreen`, `DevicePairingAdminScreen`,
`PairingScreen`, `AcceptInviteScreen`). A pass over these found no clickable-Card navigation
pattern (they use per-row `Button`s instead, which are already accessible) and no obviously
unlabeled controls, but they were not given the same line-by-line review as the flows above.
Follow-up work, not a claim that they're already fully WCAG AA compliant.

## Known limitations (explicitly disclosed, not fabricated)

- **No manual TalkBack/VoiceOver verification was performed.** This environment has no Android
  emulator or physical device with a screen reader enabled to drive interactively, and no iOS
  device/simulator with VoiceOver driven through these specific flows. The semantics changes
  above (`Role`, `onClickLabel`, `liveRegion`) are the same Jetpack Compose APIs TalkBack/VoiceOver
  are documented to respect, and are covered by the automated semantics tests, but that is not a
  substitute for an actual screen-reader run-through.
- **`LiveRegionMode` support varies by Compose Multiplatform target.** It's a mature, well-tested
  API on Android (Compose's original platform). Its behavior on iOS/Desktop through Compose
  Multiplatform's newer accessibility bridges is less battle-tested; the semantics property is
  harmless to set even where a platform doesn't fully act on it.
- **No axe-core-equivalent automated a11y scanner is wired in.** Compose has no direct equivalent
  to axe-core/Lighthouse's automated DOM accessibility scanning (there's no DOM); the closest
  automatable check is the semantics-assertion pattern this audit added
  (`AccessibilityMatchers.kt`), applied to the screens above, not exhaustively to every screen.
- **No color-contrast audit was performed.** This pass focused on keyboard/screen-reader
  operability (the ticket's explicit acceptance criteria); Material3's default color scheme is
  generally AA-compliant out of the box, but this wasn't independently verified against WCAG's
  contrast ratio formulas here.
