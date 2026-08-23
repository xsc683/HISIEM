# Repository Guidelines

## Project Structure & Module Organization

The root `src/main/java/com/xscsiem/hsiem_platform/` tree contains the Spring Boot API, grouped by feature (`alert`, `auth`, `onboarding`, `rules`, and others). Mirror those packages under `src/test/java/`. `flink/` is an independent Maven module for the detection job; its entry point is `com.siem.DetectionJob`, with tests under `flink/src/test/java/`. The Vue 3/Vite console lives in `web/`. Keep `web/src/App.vue` as a thin root, define real routes in `web/src/router/index.js`, centralize HTTP behavior in `web/src/api/index.js`, and split business pages under `web/src/views/<module>/`. Treat `infra/` as the source of truth for Docker Compose, Logstash pipelines, Elasticsearch templates, detection-rule YAML, and deployment scripts. Architecture and operational decisions belong in `docs/`. Do not commit generated `target/`, `web/dist/`, or `web/node_modules/` content.

## Build, Test, and Development Commands

Run commands from the repository root with Java 21:

```bash
./mvnw test                            # test the Spring Boot service
./mvnw spring-boot:run                 # start the API on port 8080
./mvnw -f flink/pom.xml clean package  # test and build the shaded Flink JAR
npm --prefix web ci                    # install the locked frontend dependencies
npm --prefix web run dev               # start Vite on port 5173
npm --prefix web run build             # create the production frontend bundle
```

On Windows, use `mvnw.cmd`. Use `wsl bash /mnt/d/Project/SIEM/infra/deploy.sh` only for integration deployment; see `docs/deployment.md` first.

## Coding Style & Naming Conventions

Use four-space indentation for Java, same-line braces, lowercase packages, `PascalCase` types, and `camelCase` members. Keep controllers thin and place behavior in feature services/stores. Follow the existing Vue frontend style: Composition API, two-space indentation, single quotes, no semicolons, `PascalCase` `.vue` components, and `camelCase` API/composable functions. List, form, and detail experiences should remain separate routes; do not move cross-page state into the root layout. YAML uses two spaces and kebab-case identifiers such as `rule-ssh-brute-force-001`. No repository-wide formatter or linter is configured, so avoid unrelated reformatting.

## Testing Guidelines

Tests use JUnit 5, Mockito, and Flink operator test harnesses. Name classes `*Test` and methods after observable behavior, for example `create_duplicatePort_conflict409`. Add success and failure-path tests for behavior changes. Every proposed change must state its validation command, expected result, and rollback or observability note. Run both Maven suites when changing shared schemas or detection rules. No numeric coverage gate is configured.

## Commit & Pull Request Guidelines

History follows Conventional Commit-style prefixes, chiefly `feat:`, `fix:`, and `docs:`, followed by a concise imperative summary. Pull requests should explain scope and operational impact, link the relevant issue/story, list verification commands, and include screenshots for console changes. Highlight schema, index-template, rule, port, or deployment changes explicitly; never commit credentials or local runtime state.
