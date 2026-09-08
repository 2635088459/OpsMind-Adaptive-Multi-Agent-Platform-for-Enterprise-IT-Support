# Security & secret handling

## Where secrets live, per environment

| Environment | Store | Notes |
|---|---|---|
| Local (docker compose) | `infrastructure/docker-compose/.env` | **Gitignored** (`.env`, `.env.*` in `.gitignore`; only `*.env.example` is tracked). Copy `.env.example`, fill in. |
| Kubernetes | `Secret/opsmind-shared-secrets` | Either rendered by the Helm chart from a **gitignored** `values-secrets.yaml` (`externalSecrets.enabled: false`), or owned by [external-secrets](https://external-secrets.io/) / [sealed-secrets](https://sealed-secrets.netlify.app/) (`externalSecrets.enabled: true`). See `infrastructure/helm/opsmind/README.md`. |
| CI | GitHub Actions repo/environment secrets | Never checked into the tree. |

Real credentials are **never** committed. `values.yaml` and every `*.env.example`
carry empty or obviously-fake values (`change-me`, `REPLACE_ME`,
`integration-test-secret`). A `gitleaks` job (`.github/workflows/secret-scan.yml`,
config in `.gitleaks.toml`) fails any PR/push that introduces a real one.

## Known exposure to rotate

The local `infrastructure/docker-compose/.env` on developer machines has, at
times, held a **real OpenAI API key** (used for `EMBEDDING_PROVIDER=openai` /
`CONVERSATION_REASONING_MODE=openai`). It was never committed (`git log -S`
confirms), but it has lived on disk inside a repo directory. Treat it as
potentially exposed:

1. Revoke it at <https://platform.openai.com/api-keys> and issue a new key.
2. Put the new key **only** in your local `.env` (gitignored) or a k8s Secret.
3. Set a low usage cap on the key.

## If a secret leaks

1. **Rotate immediately** at the provider (OpenAI, Keycloak client secret, DB
   password, MinIO keys).
2. Purge it from history if it was committed
   (`git filter-repo` / BFG), force-push, and tell everyone to re-clone.
3. Add the pattern to `.gitleaks.toml` only if it is a confirmed false positive.

## Reporting

Open a private security advisory on the repository; do not file a public issue.
