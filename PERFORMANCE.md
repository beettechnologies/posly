# Performance — posly

## Scope

Covers frontend performance budgets and optimization for the POS Compose Multiplatform UI
(`app/sharedUI` — Android, iOS, Desktop). **"Bundle size" and "Lighthouse p95 scores" are web-app
concepts that don't literally apply here** — there is no browser-renderable POS UI in this
codebase (the `js`/web target that exists, `app/webApp`, is an unused JetBrains wizard template
stub with none of the actual product screens; see `ACCESSIBILITY.md`'s Scope for the same
finding). This document uses the native equivalents instead: an APK size budget in place of a
bundle-size budget, and a lazy-loading/recomposition audit in place of a Lighthouse performance
audit (Lighthouse has no meaning without a page to load).

## Bundle-size budget: the Android release APK

`app/androidApp/build.gradle.kts` adds a `checkApkSizeBudget` Gradle task, wired into CI
(`.github/workflows/ci.yml`'s `android-checks` job): it assembles the release APK and fails the
build if it exceeds a budget.

- **Current measured size: ~35.8 MB** (measured directly — `./gradlew
  :app:androidApp:assembleRelease`, then reading the resulting `.apk` file's size — not a guess).
- **Budget: 40 MB** — roughly 10% headroom above the measured size, tight enough to catch a real
  regression (a large dependency or asset added by mistake) without being a hair-trigger on
  routine minor growth. Override with `-PapkSizeBudgetBytes=<bytes>` for a one-off check against
  a different threshold; raise the default in the task itself (not by routinely overriding in CI)
  if growth is deliberate.
- **Verified locally**: both the passing case (current size under budget) and the failing case
  (`-PapkSizeBudgetBytes=1000`, forced failure) were run against a real local build before this
  shipped.

### Known limitation: R8/minification is off

`isMinifyEnabled = false` in the release `buildType` — the 35.8 MB figure above is an
**unminified** release build; `proguard-rules.pro` is currently just the default template with no
project-specific keep rules. Enabling R8 minification + resource shrinking would very likely
shrink this substantially, but **was not flipped on as part of this work**: this codebase uses
Koin (reflection-based DI) and kotlinx.serialization (reflection-based serializers), both of
which are known to need careful ProGuard/R8 keep rules to survive minification without breaking
at runtime — and this environment has no Android emulator/device available to install and
actually exercise a minified release build to confirm nothing broke. Flipping the flag without
that verification risks shipping a build that silently fails DI resolution or serialization in
ways a JVM-only test suite wouldn't catch. Recommended follow-up, done with real keep rules and a
real device/emulator regression pass — not done speculatively here.

## Lazy-loading / recomposition audit

Audited every `.forEach`/`.forEachIndexed` call inside a `@Composable` across `app/sharedUI` (the
literal "lazy-loaded components" criterion) to check whether any large/unbounded collection is
rendered in a plain `Column`/`Row` instead of `LazyColumn`/`LazyRow`.

**Result: no fix needed.** Every collection that can genuinely grow large already uses
`LazyColumn`/`LazyRow` with `items(...)`:

- `SaleScreen.kt` — search suggestions, cart items
- `RefundScreen.kt` — refund line items
- `TransactionListScreen.kt` — order history
- `UserListScreen.kt`, `StoreListScreen.kt`, `TaxProfileListScreen.kt`, `DeviceListScreen.kt`,
  `FeatureFlagListScreen.kt` — their respective entity lists

Every remaining `.forEach` renders a collection that's inherently small and bounded by the
domain, not by pagination — tax profile rates (a handful per profile), the store-picker dropdown
(a handful of stores per org), a fixed role list, a schedule list for one store, a receipt's line
items, an order's payment records, a "top 5" products list, SSO role mappings, CSV import column
headers. Forcing these into `LazyColumn` would add complexity (scroll-state management inside a
dropdown/dialog) for collections that will never be large enough to cause a recomposition or
scroll-performance problem. This was a genuine audit with a clean result, not an assumption.

## Known limitations (explicitly disclosed, not fabricated)

- **No Baseline Profiles.** Would reduce cold-start JIT/interpretation cost on Android; not
  implemented here as it requires generating and maintaining a profile from real device runs,
  which is out of scope for this pass and needs a real device/emulator to produce meaningfully.
- **No Compose recomposition tracing/`@Stable`/`@Immutable` annotation pass.** None of this
  app's UI state classes carry `@Stable`/`@Immutable`; whether that's actually causing excess
  recomposition would need the Compose Layout Inspector's recomposition counts on a real running
  app to diagnose, not a static code read. Not verified here.
- **No macrobenchmark / startup-time CI gate** — the native equivalent of a Lighthouse
  performance score gate. Would need Android's Macrobenchmark library and a real
  device/emulator in CI to produce trustworthy numbers; not set up here.
- **iOS bundle size (IPA) is not budgeted** — only the Android APK. This environment has no
  Xcode/iOS build toolchain to produce and measure a real IPA (a documented constraint elsewhere
  in this repo's iOS `.ios.kt` actuals - see `app/sharedUI/src/iosMain/kotlin/com/beettechnologies/posly/format/Formatters.ios.kt`'s
  own doc comment on the same constraint). If iOS shipping becomes real, an analogous
  `xcodebuild`-based size check belongs alongside the Android one.
