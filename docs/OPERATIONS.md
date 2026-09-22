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
| `PROK_BRAIN_PORT` | port | `8080` |
| `PROK_BRAIN_LOG` | rotating log file | console only |
| `PROK_BRAIN_LOG_LEVEL` | log level | `INFO` |
| `PROK_BRAIN_BACKUPS` | where backups go | `C:\ProkNetBrain\backups` |
| `PROK_BRAIN_PUBLIC_URL` | for `status.ps1` | loopback |
| `PROK_CONFIG_PUBLIC_KEY` | parser-rule key override | the pinned key |

No credential is ever a command-line argument, and none is in git.

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

The Brain speaks plain HTTP and binds to loopback by default. **Anything public belongs
behind an HTTPS reverse proxy** (Caddy or nginx), and it warns in the log if you bind it
to anything else.

Status: **deployment ready, TLS hostname pending.** There is no public hostname or
certificate for the pilot yet, and no temporary insecure public HTTP has been hard-coded
anywhere to paper over that.
