# SPEC-MK-016 API Contract

## API Scope

This spec scopes API work to the `versioned-memory` capability. If it exposes no HTTP API, entry must be through an application service or event consumer.

## Common Constraints

- Internal APIs must carry correlation id.
- Admin APIs must carry actor id and write audit.
- Search/Context APIs return only redacted snippets and provenance.
- Runtime may read evidence but cannot directly write active memory.
