# Repository Guidelines

## Project Structure & Module Organization

This uni-app Vue 3 TypeScript client lives in `src/`. Pages are under `src/pages/`, platform capabilities under `src/services/`, shared request code under `src/api/`, and contract-aligned types under `src/types/`. Vitest tests live in `tests/`. `contracts/openapi.yaml` remains the authoritative V0.1 API definition; planning and acceptance criteria live in `docs/`.

## Build, Test, and Development Commands

Use Node 22 LTS and pnpm 11:

```powershell
pnpm dev:h5
pnpm dev:mp-weixin
pnpm typecheck
pnpm test:run
pnpm build:mp-weixin
```

Import `dist/dev/mp-weixin` into WeChat DevTools for development. Validate the contract with `py contracts/validate_openapi.py`; it must print `CONTRACT_OK`.

## Coding Style & Naming Conventions

Use 2-space indentation for YAML, JSON, Vue, and TypeScript; retain 4 spaces for Python. Prefer TypeScript strict mode, Vue Composition API, and small single-purpose modules. Name Vue components in PascalCase (`RecommendationCard.vue`), composables with a `use` prefix (`useLocation.ts`), and variables/functions in camelCase. Keep API field names exactly as specified in `openapi.yaml`. Never place the Amap Web Service key or server-side risk/ranking logic in client code.

## Testing Guidelines

Run the contract validator after every API change. Add focused tests with each new behavior, especially anonymous UUID headers, GCJ-02 location handling, reroll exhaustion, feedback submission, and loading/error states. Name TypeScript tests `*.spec.ts`. No coverage threshold exists yet; new tooling should establish one before CI enforcement.

## Visual Review Guidelines

For charts and other primarily visual UI work, default to user-led manual review. Complete the necessary code, build, type, and automated behavior checks, then provide a concise manual acceptance path and let the user judge appearance. Do not proactively control a browser, capture screenshots, or perform automated visual inspection unless the user explicitly identifies the work as a long-running task or asks to use Goal mode. Automated checks may still verify non-visual behavior, accessibility, and data correctness.

## Commit & Pull Request Guidelines

The existing history uses Conventional Commit style (`docs: define v0.1 api contract`). Continue with concise prefixes such as `feat:`, `fix:`, `test:`, `docs:`, and `chore:`. Keep commits narrowly scoped. Pull requests should explain behavior and contract impact, link the relevant task or issue, list verification commands, and include screenshots for UI changes. Call out configuration changes and never commit secrets or generated build output.

## Imported Claude Cowork project instructions
