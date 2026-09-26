# ProkNet operations

What has to be true on the VPS, and the one secret this project has.

## The receipt-rule configuration key

MTN or Airtel will reword a message one day. When they do, the parser dictionaries
are replaced by **signed data** instead of a new APK. One keypair makes that safe.

| | |
|---|---|
| Curve | NIST P-256 (secp256r1), ECDSA / SHA-256 |
| Key id | `9410c707` |
| Public key | `9d536299…9533aa0c` (128 hex chars) |
| Pinned in the app | `ReceiptRules.PINNED_CONFIG_KEY` |
| Held by the server | `brain/app.py`, `CONFIG_PUBLIC_KEY` — **public half only** |
| Private key | `C:\ProkNetKeys\receipt_rules_config_key.pem` on the VPS, **not in git** |

It is a **dedicated** key. Not the Brain's transport identity, and not any user
identity. That separation is the whole security argument: whoever takes the public
server can withhold or delay a configuration — phones survive that, because the
built-in rules keep working — but can never forge one.

### Where Mike must keep the private key

The file is at `C:\ProkNetKeys\receipt_rules_config_key.pem`. It is outside the
repository on purpose, and `.gitignore` refuses `*.pem` as a second line of defence.

Rules:

1. **Never commit it.** A key in git history is a key that is gone, even after a
   later delete. `tests/test_crosslang.py` scans the whole tree for private-key
   markers on every test run, so this fails loudly rather than quietly.
2. **Back it up somewhere that is not the VPS.** If the VPS dies with the only
   copy, parser wording can never be updated again without shipping a new APK with
   a new pinned key.
3. **Do not put it on a phone**, and do not paste it into a chat.
4. If it is ever exposed, the recovery is a new keypair, a new `PINNED_CONFIG_KEY`,
   a new `CONFIG_PUBLIC_KEY` and a new APK. There is no revocation list; the pinned
   key *is* the trust. That is the cost of the design, and it is the right cost —
   the alternative is a server that can rewrite how phones recognise money.

## Publishing new parser rules

Write the wording as plain JSON:

```json
{
  "version": 3,
  "validFrom": 1758400000000,
  "terms": {
    "credit": ["vous avez recu", "fonds arrives"],
    "currency": ["FCFA", "XAF"]
  }
}
```

Then, on the machine that holds the key:

```powershell
$env:PROK_CONFIG_PRIVATE_KEY_FILE = "C:\ProkNetKeys\receipt_rules_config_key.pem"
python -m brain.publish_rules rules.json --db C:\ProkNetBrain\brain.db
```

The key never appears as an argument, so it cannot end up in shell history or in a
process listing. `PROK_CONFIG_PRIVATE_KEY` (the PEM text itself) works too, for a
deployment where a file is impossible.

`--dry-run` signs and prints without changing anything. `--url https://brain…`
posts to a running Brain instead of writing its database, for the case where the
admin machine is not the server.

What happens then:

```
admin, offline, with the private key
    -> validates against the same bounds the phone enforces
    -> signs the canonical bytes
    -> Brain verifies against the PUBLIC key before storing
    -> phone downloads and verifies AGAIN against the key pinned in the APK
```

Two verifications of one signature, by two parties who trust each other about
nothing. The phone's is the one that matters.

Things the tool refuses, on purpose:

- a key that is not the one this deployment pins — it says so and signs nothing,
  because publishing a configuration every phone will reject is worse than failing;
- a version that is not newer than what is already published;
- a term longer than 48 characters, a category with more than 64 terms, a control
  character, or a quote or backslash (those would need JSON escaping, and escaping
  is where two independent canonicalisations drift apart);
- anything that is not a word list. No regex, no expressions, no code. A remotely
  supplied rule that could execute would be a code path into the part of every
  phone that decides whether money arrived.

### Rolling back

You cannot publish an older version over a newer one — that is a downgrade attack,
and the phone refuses it too. To undo wording, publish a **higher** version
containing the old terms.

## The Brain itself

```powershell
python -m brain.app --host 127.0.0.1 --port 8080 --db C:\ProkNetBrain\brain.db
```

Put it behind an HTTPS reverse proxy. It never listens for anything else.

Environment it reads:

| Variable | Meaning |
|---|---|
| `PROK_CONFIG_PUBLIC_KEY` | overrides the pinned configuration public key (test deployments) |

The Brain never reads `PROK_CONFIG_PRIVATE_KEY*`. That is asserted by a test.

## What is a secret and what is not

| | |
|---|---|
| Configuration **private** key | the one secret. Offline, never in git, backed up off-box. |
| Configuration **public** key | not secret. Committed in two places on purpose. |
| A node id | **not a secret.** Both phones hold it and it is derived from signed bytes. Nothing may be readable merely because somebody knows one — that is why `/v1/wallet`, `/v1/settlements/{id}` and `/v1/pay/*` all require a signed request. |
| A settlement id | not a secret, same reasoning. |
| A seller's Mobile Money number | real-world identifying data. Readable only by somebody who actually owes that seller. |
| Operator webhook secret | not configured yet, so no webhook can be verified and none may confirm a payment. |

## v0.17.0 running the live Brain

The control plane is what makes ProkNet a network rather than two phones that happen to
meet, so from v0.17 the Brain is something that has to stay up.

### Install once

As Administrator, once:

```powershell
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\deploy\brain\install.ps1
```

That registers a Windows **Scheduled Task** called `ProkNetBrain` that starts at boot
and restarts up to three times a minute apart if it dies. A Scheduled Task on purpose:
it is built into Windows and needs no extra dependency, and this box also runs live
services that a casually installed service wrapper could disturb.

### Day to day

| | |
|---|---|
| `start.ps1` | start it now (refuses if already running) |
| `stop.ps1` | stop it, then **check** it actually stopped |
| `status.ps1` | process, listening socket, and `/health` |
| `backup.ps1` | a consistent snapshot, outside the repository |

`status.ps1` checks the listening socket rather than trusting the process, because a
python that is alive and not listening looks healthy and serves nobody. `stop.ps1`
counts processes afterwards for the same reason.

### Configuration

| Variable | Meaning | Default |
|---|---|---|
| `PROK_BRAIN_DB` | database file | `C:\ProkNetBrain\brain.db` |
| `PROK_BRAIN_BIND` | interface | `127.0.0.1` |
| `PROK_BRAIN_PORT` | port | `8080` (the pilot VPS uses **8081**, see below) |
| `PROK_BRAIN_LOG` | rotating log file | console only |
| `PROK_BRAIN_LOG_LEVEL` | log level | `INFO` |
| `PROK_BRAIN_BACKUPS` | where backups go | `C:\ProkNetBrain\backups` |
| `PROK_BRAIN_PUBLIC_URL` | for `status.ps1` | loopback |
| `PROK_CONFIG_PUBLIC_KEY` | parser-rule key override | the pinned key |
| `PROK_TREASURY_IDS` | v0.18.0: comma-separated node ids allowed to act as the **treasury** (record top-ups, move withdrawals past REQUESTED, post test credit) | none - nobody is treasury |
| `PROK_TEST_IDS` | v0.18.0: node ids that may receive audited **test credit** (pilot phones only) | none |
| `PROK_PAYMENTS_LIVE` | v0.18.0: `1` lets an observed top-up credit a customer. Anything else records it as REJECTED (`payments_disabled`) and credits nobody | off |
| `PROK_TREASURY_MSISDN_MTN` / `PROK_TREASURY_MSISDN_AIRTEL` | v0.18.1: the treasury wallet numbers a customer is told to send to. Shown by the app ONLY while `PROK_PAYMENTS_LIVE=1` AND the identity is in the pilot allowlist; before that the app says top-ups are not open | none |
| `PROK_PILOT_IDS` | v0.18.2: the **pilot allowlist** - node ids that may move real money. Even with `PROK_PAYMENTS_LIVE=1`, an identity not listed here is shown no treasury number, and a valid operator message for it is held for review, never credited automatically (and a treasurer cannot credit it until it is listed). Enforced on the server | none - nobody |

No credential is ever a command-line argument, and none is in git.

### v0.18.0 the ledger, the treasury phone and the payments switch

The Brain now keeps the Prok ledger (schema 5): customer credit, holds, earnings,
withdrawal requests, observed top-ups, an audit table. **It cannot send money.** A
withdrawal is a row a person still has to send by hand from the Mobile Money app; the
queue says "N retraits en attente = N envois manuels" and means it.

Roles are the server's, from the environment above, never from the app:

- a **treasury identity** is the node id of the phone holding Prok's SIMs. Find it on
  that phone's Lab screen (`me: prok-…` is the short form; the full id is in the network
  diagnostic), put it in `PROK_TREASURY_IDS`, restart the Brain. The Lab screen's
  TRÉSORERIE button appears on the next sync, and the phone starts forwarding the
  operator's own "vous avez reçu / envoyé" messages as amounts and hashes - never text;
- a **test identity** may receive test credit from the treasury screen so holds, sessions
  and withdrawals can be exercised with nobody's money. List the pilot phones there;
- `PROK_PAYMENTS_LIVE` stays **unset** until the legal and operational questions in
  `docs/PAYMENTS_V018_DESIGN.md` Appendix B are answered in writing. Mike flips it, not a
  script. While it is off the customer app shows no treasury number.

Set them as **machine** environment variables like `PROK_BRAIN_PORT`, so the scheduled
task sees them at boot, then `stop.ps1` / `start.ps1` and check `/health` reports
`"schema": 5`.

What is logged from the ledger: ids cut short, amounts, states, the actor. Never a
number, never a message body. The withdrawal row holds the payee's number because the
treasurer has to type it; it is returned only to treasury identities and never written
to the audit table (a test walks the audit rows for it).

### v0.18.1 upgrading the pilot Brain (schema 5 → 6), and rolling back

Migration 6 adds one column (`ledger_withdrawals.kind`, default `WITHDRAWAL`). It is
applied by the Brain on start, like every migration. The order that keeps a way back:

```powershell
$env:PROK_BRAIN_PORT = '8081'
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\deploy\brain\backup.ps1   # note the file name it prints
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\deploy\brain\stop.ps1
git -C C:\Projects\ProkNet checkout v0.18.1
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\deploy\brain\start.ps1
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\deploy\brain\status.ps1   # must print schema=6, version=0.18.1
```

**Rollback**, if anything is wrong after the upgrade: `stop.ps1`, then
`restore.ps1 -From <the backup printed above>` (it refuses while the Brain runs, keeps the
current file as `brain-replaced-<stamp>.db`, and checks the backup's integrity before
copying), then `git checkout v0.18.0` if the code must go back too, then `start.ps1`.
Schema 6 on v0.18.0 code is harmless (the column is ignored); schema 5 on v0.18.1 code
is repaired by the migration on start. Either direction is reversible from the backup.

**Daily** (treasurer, from the app): the typed balance per rail. **Weekly** (Mike):
`backup.ps1` and a glance at `status.ps1`. Backups rotate at 30.

### Recovering a person's balances after a reinstall or a lost phone

A reinstalled phone has a new identity and starts at zero. Two ways back, both leave
an audit row and neither duplicates money:

- **by number:** the person tops up from the number they used before; the treasury
  phone sees a tag for the new identity from a number bound to the old one and holds it
  for review (`rebind_candidate`); the treasurer confirms in "Messages à vérifier"; the
  old identity's credit and earnings MOVE to the new one, and the number follows;
- **by hand:** TRÉSORERIE → long press *Crédit test* → "Transférer une identité" with
  both full ids and how the person proved themselves. Refused while the old identity has
  an open hold or an open withdrawal/refund.

### v0.18.2 what an approval needs

A withdrawal or refund is approved only against a wallet balance the treasurer typed
**for that rail, within the last 24 hours, not below what the ledger expects**. No
balance today, a balance older than a day, or a balance below the ledger each refuse the
approval with the reason; the treasury screen's reconciliation line says which. This is
the daily "Solde du jour" made mandatory rather than advisory: a wallet nobody looked at
today does not vouch for a send, and a parsed message is not proof.

### Access control, in one table

| Who | May |
|---|---|
| Any signed identity | read its own wallet; request a hold on a BUYER's credit as the seller of that session; request its own withdrawal or refund; register a top-up intent; claim an unassigned payment (number + amount + reference, then a treasurer decides) |
| A treasury identity (`PROK_TREASURY_IDS`) | everything above plus: the queue, approve / deny / mark sent / not sent / confirm paid, record an observed credit or debit, review a claim, post test credit to a TEST identity, type a balance, reverse a posting with a memo, move an identity, read the audit and the reconciliation |
| Nobody | send money, change a posting, delete anything, credit a customer while `PROK_PAYMENTS_LIVE` is off, credit an identity outside `PROK_PILOT_IDS`, credit a customer from a number alone, approve a send without today's typed balance on that rail |

### Backups

`backup.ps1` uses SQLite's own online backup API, **not** a file copy. Copying
`brain.db` while the server is writing gives you a file that opens and is quietly
wrong - a torn page in the middle of a settlement is worse than no backup, because you
would not find out until you needed it.

One snapshot covers everything: the v0.16 financial tables and the v0.17 network tables
share one file and one transaction boundary, so they must share one snapshot. The last
30 are kept. Nothing secret is printed.

### Logs

`PROK_BRAIN_LOG` turns on a `RotatingFileHandler` capped at 8 MB with 5 backups. Logs
that grow until they fill the disk have already cost this project a day on this very
box.

Logged: a demand created, an activation offered, accepted, declined or expired, a
connection reported, presence expiring. Ids are cut to twelve characters - enough to
follow one request, not enough to be a directory of who was where. Never logged: a
Mobile Money number, an SMS body, a private key, a request signature, or a position.

### TLS

The Brain speaks plain HTTP and binds to loopback. **Anything public belongs behind an
HTTPS reverse proxy**, and it warns in the log if you bind it to anything else.

Status: **live, 2026-09-22.**

| | |
|---|---|
| Public URL | `https://proknet.duckdns.org` |
| Certificate | Let's Encrypt, obtained and renewed automatically by Caddy |
| Proxy | `C:\Caddy\caddy.exe`, config `C:\Caddy\Caddyfile` |
| Brain | `127.0.0.1:8081`, never a public interface |
| Plain HTTP | 308-redirects to HTTPS; there is no insecure path |

The Caddy site block is three lines:

```
proknet.duckdns.org {
	reverse_proxy localhost:8081
}
```

**Port 8081, not 8080.** The pilot VPS is shared with other live services and 8080 is
already Open WebUI. Binding the Brain there fails with `WinError 10013` - which is the
right outcome, and `start.ps1` now says so instead of reporting a PID that has already
exited. `PROK_BRAIN_PORT` is set to `8081` as a **machine** environment variable so the
scheduled task sees it at boot.

That box also serves `mobali.duckdns.org` and `owltrader.duckdns.org` from the same
Caddy. Reload with `caddy reload`, never a restart, and check all three afterwards -
one config file holds all of them.

#### The DuckDNS token

`proknet.duckdns.org` is a DuckDNS name. The token that controls it lives in
`C:\ProkNetKeys\duckdns_token.txt`, outside this repository, readable only by
Administrator and SYSTEM. It is **not** in git and must never be.

It controls *every* domain on that DuckDNS account, including the two live ones above,
so it is a credential of the same weight as a signing key. Rotate it on the DuckDNS page
and update that file; nothing in the Brain reads it at runtime.

A DuckDNS name takes the IP of whoever calls the update URL. Creating it from a phone
points it at the phone's mobile IP, which is what happened here first. Set it explicitly:

```
curl "https://www.duckdns.org/update?domains=proknet&token=<token>&ip=<vps ip>"
```

#### Verifying it end to end

```
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\deploy\brain\status.ps1
curl https://proknet.duckdns.org/health
```

`/health` must report the version and schema. Then check the two things a reverse proxy
can quietly break:

- an **unsigned** request to `/v1/network/jobs` must return
  `this endpoint requires a signed request`;
- a **signed GET with a query**, such as `/v1/network/coverage?zone=...`, must return
  200. The signature covers the canonical target including the query, so a proxy that
  rewrites either one breaks every phone at once. Both were verified on 2026-09-22.
