# Ridestr Skills Registry

**Note**: This is a human-readable index. Claude automatically discovers skills based on their `description` field in the SKILL.md frontmatter.

---

## Available Skills

| Skill | Location | Purpose |
|-------|----------|---------|
| **add-nostr-sync** | `add-nostr-sync/SKILL.md` | Adding new Nostr-synced data types |
| **cashu-wallet** | `cashu-wallet/SKILL.md` | Debugging balance, payment, HTLC, P2PK signature issues |
| **deploy** | `deploy/SKILL.md` | Deploy test builds to Android over Tailscale |
| **documentation-updater** | `documentation-updater/SKILL.md` | Keeping docs up to date after code changes |
| **ridestr-protocol** | `ridestr-protocol/SKILL.md` | Debugging ride state, events, cancellations |

---

## Skill Discovery

Claude discovers skills automatically when requests match keywords in their `description` field:

### add-nostr-sync
- "add sync", "new event kind", "new backup event"
- "ProfileSyncManager", "SyncableProfileData"
- "sync adapter", "synced data type"

### cashu-wallet
- "balance wrong", "payment failed", "HTLC", "escrow"
- "wallet", "ecash", "NIP-60", "P2PK", "signature"
- "deposit", "withdraw", "proofs", "mint API error"
- "proofs could not be verified", "non-hexadecimal"
- "wallet pubkey", "claim failed"

### deploy
- "deploy rider", "deploy driver", "deploy both"
- "send build", "install on device", "push to phone"
- "test on device", "wireless ADB", "Tailscale deploy"

### documentation-updater
- "update docs", "sync documentation"
- "add connection map", "new module added"
- "document this change"

### ridestr-protocol
- "phantom cancellation", "state bug"
- "event not received", "ride stuck"
- "subscription", "confirmation event ID"

---

## Skill Format (Official Anthropic)

Each skill uses the official format:

```
skills/
├── skill-name/
│   └── SKILL.md  ← Required filename
```

With frontmatter:
```yaml
---
name: skill-name
description: What this skill does and when to use it. Include trigger keywords.
---
```

---

## Related Documentation

- [docs/CONNECTIONS.md](../../docs/CONNECTIONS.md) - Module dependency map
- [docs/README.md](../../docs/README.md) - Documentation index
- [common/src/main/README.md](../../common/src/main/README.md) - Shared module docs
