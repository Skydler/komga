# Project Guidelines

Run every build, test, lint, and dev-server command through the `./dev` container wrapper. See `.devcontainer/AGENTS.md`, which `.opencode/opencode.jsonc` loads. Keep `dev`, `.devcontainer/`, and `plans/` out of commits. They are local-only.

## Fork maintenance
- This repository is a self-maintained fork (`fork` remote: `Skydler/komga`) of `gotson/komga` (`origin` remote). We do not push to upstream or open pull requests there. Upstream is only a source of changes to merge in.
- `sk-main` is the fork's main branch. Each push to it runs `.github/workflows/fork-docker.yml`, which builds an arm64 image and pushes it to `ghcr.io/skydler/komga` (`latest` and `sha-<commit>` tags).
- The upstream release and publish workflows were deleted from the fork: `release.yml`, `chromatic*.yml`, `dispatch.yml`, `dockerhub_description.yml`, `github-releases-to-discord.yml`, `lock.yml`, `browserlist-update.yml`, `stale.yml.bak`. They need upstream secrets, or they act on upstream services. When an upstream merge gives a modify/delete conflict on one of them, keep it deleted (`git rm <file>`). Do not add them back.
- The kept workflows are `test-komga.yml`, `test-nextui.yml`, `test-webui.yml`, and `validate-nextui-i18n.yml` (CI checks), and `fork-docker.yml`.

## Project structure
- `komga`: Spring Boot backend in Kotlin, built with Gradle and JDK 21. Packages are `domain`, `application`, `infrastructure`, and `interfaces`. ArchUnit tests in `komga/src/test/kotlin/org/gotson/komga/architecture/` enforce the layering. For example, `domain..model` must not depend on the other layers, and `*Controller` classes must be in `..interfaces..`.
- `komga-tray`: Compose desktop wrapper. It is the second Gradle module.
- `komga-webui`: Legacy Vue 2 frontend that uses Node 22. Its only scripts are `serve`, `build`, `test:unit`, and `lint`.
- `next-ui`: New Vue 3 frontend that uses Node 24. Also read `next-ui/AGENTS.md` and `next-ui/README.md`. All of its npm scripts need `npm run` (for example `npm run test:unit`), not `npm test:unit`.
- The backend serves both frontends. The Gradle tasks `webuiCopyIndex` and `nextuiCopyIndex` copy each `dist/` into `komga/src/main/resources/public/`, which is gitignored. Next UI becomes `index-next.html`.

## Backend
- Full CI check: `./dev ./gradlew build :komga-tray:jar`. This also runs ktlint.
- Single test: `./dev ./gradlew :komga:test --tests 'org.gotson.komga.SomeTest'`.
- Fix formatting: `./dev ./gradlew ktlintFormat`. Kotlin uses 2-space indentation and trailing commas (see `.editorconfig`).
- Flyway migrations live in `komga/src/flyway/`, not in `src/main/resources`. The `resources/db/migration/sqlite` folder holds SQL migrations and `kotlin/db/migration/sqlite` holds code migrations. The `tasks` DB has a separate folder. Name new migrations `V<yyyyMMddHHmmss>__name.sql`.
- jOOQ classes are generated at build time from a SQLite DB that the migrations create. After a schema change, run a build so the generated classes update. Do not edit `build/generated-src`.
- Tests use the `test` profile, which creates temporary SQLite files. Dev runs use `--spring.profiles.active=dev,noclaim`, which uses an in-memory DB.

## Next UI
- CI runs `test:unit`, `test:storybook`, `lint`, `prettier`, `type-check`, `build`, and an i18n check.
- i18n: the ESLint plugin generates message IDs, so do not write IDs by hand. After you change UI strings, run `npm run formatjs:extract` and commit `next-ui/i18n/en.json`. CI fails if this file differs from the source. Only edit `en.json`. Weblate supplies the other locales. `src/i18n` is compiled output and is gitignored. Run `npm run i18n:compile` before `npm run dev`.
- The API client in `src/generated/openapi` is generated. When the backend API changes, update `komga/docs/openapi.json` with `./dev ./gradlew :komga:generateOpenApiDocs`, and then run `npm run openapi-ts` in `next-ui`. Do not edit the generated files by hand.

## Commits
- Use Conventional Commits with scopes such as `feat(kobo):`, `fix(api):`, and `feat(nextui):`.
