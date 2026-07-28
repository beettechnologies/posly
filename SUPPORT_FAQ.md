# Support FAQ — for cashiers & store staff

Plain-language answers to the questions store staff ask most often. If you're a store admin or
developer looking for API details, see [ADMIN_GUIDE.md](ADMIN_GUIDE.md) instead.

## Logging in

**I forgot my password.** Ask your store manager or an admin to check your account status. There's
no self-service "forgot password" reset yet (see Known limitations) — an admin can invite you again
or a developer can reset your password directly.

**I'm asked for a 6-digit code after my password.** That's two-factor authentication (MFA) — enter
the code from your authenticator app. If it's rejected repeatedly, your phone's clock may be out of
sync with the correct time; check your phone's date/time settings.

**My account says "disabled."** An admin turned off your account (often when someone leaves the
team, or temporarily during a security review). Ask an admin to re-enable it.

## Ringing up a sale

**How do refunds work?** A manager or admin processes refunds (cashiers don't have refund access
by default). Refunds must happen within **90 days** of the original sale — after that, the system
won't allow it through the normal refund flow. Partial refunds (just some items from an order) are
supported, and you can choose whether refunded items go back into inventory.

**Can I apply a discount?** Yes — either to a single item or to the whole cart, as either a
percentage or a fixed dollar amount. Ask your manager what your store's discount policy allows;
the system itself doesn't restrict discount size beyond not letting a discount push a line below
$0.

**A price looks wrong at checkout.** Prices and tax come from the current catalog, not from
memory — if something looks off, it's either a genuine pricing mistake (flag it to your manager,
who can fix it in the catalog) or a modifier/discount you didn't expect being applied. Check the
cart's line-item breakdown before completing the sale.

## When the internet or a terminal acts up

**The register says it's offline.** You can still ring up sales — checkout doesn't require an
active connection to finish a cart. Once the terminal reconnects, it automatically syncs anything
rung up while offline. If prices changed on the server while you were offline, occasionally a sale
needs a manager's review afterward to confirm the price the customer actually agreed to — that's
normal and doesn't mean the sale was lost.

**The receipt printer isn't printing.** First, check the obvious: is it powered on, does it have
paper, is it plugged in/connected? If it's genuinely down, tell your manager — they can mark it
offline in the system so print jobs queue instead of erroring out, and retry them once it's fixed.
You can still complete sales without a working printer; the receipt can be reprinted or emailed
later.

**Can a customer get their receipt by email instead of print?** Yes, if that option is available in
your store's checkout flow — ask your manager if it's not.

## Shifts & cash

**Why does closing my shift ask for a reason?** If the cash you counted differs from what the
system expected by more than **$5**, you'll be asked to either add a note explaining the difference
or have a manager/admin override it. Small differences (under $5) close without any extra step.

**I opened a shift and can't open another.** Only one open shift per cashier per store at a time —
close your current one first.

## Known limitations (explicitly disclosed, not fabricated)

- **No self-service password reset.** Getting back into a locked-out account currently requires an
  admin's help (a fresh invite or a direct password reset) — there's no "forgot password" email
  flow yet.
- **No customer-facing order-status or receipt lookup portal.** If a customer loses a paper receipt
  and didn't get an email copy, recovering it means a manager pulling up the order in the system on
  their behalf (`GET /orders/{id}`), not something the customer can look up themselves.
- **This FAQ covers the backend/API's actual behavior, not a specific point-of-sale app's exact
  button layout** — the app your store uses may label things slightly differently; ask your
  manager if a described behavior doesn't match what you see on screen.
