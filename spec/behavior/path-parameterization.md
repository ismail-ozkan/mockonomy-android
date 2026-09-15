# Behavior: Path Parameterization Suggestions

> Implementation: `com.mockonomy.engine.PathParameterization` (`:engine`, pure Kotlin)

This is an **authoring-time** transformation, not a **serve-time** one: when a mock is
created from a request log, it **suggests** generalizing a concrete path (`/users/42`) into a
parameterized route (`/users/:userId`). Matching itself does not change — the engine already
matches `:param` segments according to [matching.md](matching.md). This document only
defines "which segment is a parameter candidate and what is the suggested name". Every
platform must produce the same suggestion, so the rule lives here.

A suggestion is always **a suggestion**: the user accepts it with one tap or keeps the
literal path (a UI decision). The engine side only provides deterministic detection and
naming.

---

## Segment detection

The path is split on `/` (leading/trailing `/` are ignored — the same as
[matching.md](matching.md)). For each segment, in this order:

1. Already a parameter (starts with `:`) → left untouched.
2. **Numeric**: the segment is not empty and every character is `0-9` → parameter candidate.
3. **UUID**: an `8-4-4-4-12` hexadecimal pattern (case-insensitive) → parameter candidate.
4. Otherwise a static segment.

Deliberately **narrow**: only numbers and UUIDs. Word segments such as `abc`, `me` or
`search` do not count as parameters — a false positive costs more than a false negative
(the user would turn a literal resource into a wildcard by accident). Formats such as slugs,
dates and base64 are deliberately **left out**.

---

## Naming

The name of a parameter segment is derived from **the nearest static segment before it**:

```
/users/42          → previous static "users"  → :userId
/orders/7c9e-...   → previous static "orders" → :orderId
```

Rule:
- `base` = the previous static segment, **singularized** (naively: if it is longer than 1
  and ends with `s`, the last `s` is dropped; `users`→`user`, `orders`→`order`). The naivety
  is accepted: cases like `categories`→`categorie` are not perfect, but the name is only for
  readability and does not affect matching.
- Name = `base` + `Id` (e.g. `userId`, `orderId`).
- If there is no previous static segment (the path starts with a parameter), the name is
  `id`.
- If names collide within the same path, a number is appended: `userId`, `userId2`, …

---

## Example

```
Recorded:    /api/v1/users/42/orders/7c9e4f1a-1234-4abc-8def-1234567890ab
Suggestion:  /api/v1/users/:userId/orders/:orderId
Parameters:  userId=42 (index 3), orderId=7c9e... (index 5)
```

`api`, `v1`, `users` and `orders` stay static; only `42` and the UUID become parameters.

---

## Out of scope

- Suggesting a `{{path.userId}}` template for matching values in the body.
- Detecting formats other than numbers and UUIDs (slugs, dates).
