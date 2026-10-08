# Development

Working on the Synara server itself: cloning, running, testing, and the workflows around generated files and the `common-rpc` submodule. For the module map and request flow, see [ARCHITECTURE.md](ARCHITECTURE.md). For building a client, see [docs/README.md](README.md).

## Clone

`common-rpc` is a git submodule ([`.gitmodules`](../.gitmodules) → `synara-common-rpc`), shared with the client apps. Clone with:

```bash
git clone --recurse-submodules <repo-url>
```

If you already cloned without it: `git submodule update --init --recursive`. Forgetting `--recurse-submodules` is the most common bootstrap failure — the build fails with `common-rpc` sources missing.

## Prerequisites

- JDK 25 (`server/build.gradle.kts` sets `sourceCompatibility`/`targetCompatibility` to `VERSION_25`; CI builds with Temurin 25).
- Docker and Docker Compose for the dev stack and for the PostgreSQL tests (Testcontainers).

## Configuration

Copy [`example.env`](../example.env) to `.env` and fill in the variables you need. `CLIENT_ID`/`CLIENT_SECRET` (`client.id`/`client.secret`) seed the first admin account on startup (`DatabaseManager.init()`, `server/src/main/kotlin/dev/dertyp/services/DatabaseManager.kt` lines 18-33, inserted with `insertIgnore` so only the first run against an empty database creates it); without them a fresh server has no user to log in with. See [ENVIRONMENT_VARIABLES.md](ENVIRONMENT_VARIABLES.md) for the rest of the variables, what each one does, and its default.

## Running

| Method | Command | Notes |
|---|---|---|
| Gradle | `./gradlew :server:run` | Runs the server alone against whatever `.env`/config you have set up. |
| `dev.sh` | `./dev.sh` | Builds `:proxy:installDist` and `:server:installDist`, loads `.env`, then runs both the proxy and the server together; `Ctrl+C` stops both. |
| Docker Compose (dev stack) | `docker compose up` | `docker-compose.yml`: builds the server from source plus PostgreSQL, the Redis cluster, and the `transcriber`/`audio-embed`/`recsys` sidecars. |
| Docker Compose (built images) | see `docker-compose.server.yml` / `docker-compose-prod.yml` | Run the `ghcr.io/dertyp7214/synara*` images instead of building locally; `-server.yml` puts services on a `proxy` network for use behind `:proxy`, `-prod.yml` uses `network_mode: host`. |

## Git hooks

`./gradlew installGitHooks` points git at `.githooks` (`git config core.hooksPath .githooks`). The `pre-commit` hook runs `generateDocs` whenever staged changes touch `server`, `common-rpc` or `plugin-api` sources, an `application.yaml` file, `Dockerfile.nobuild`, or one of the build scripts, and stages every regenerated output. Before that, the hook formats the staged Kotlin files with IntelliJ's formatter and the project code style (`.idea/codeStyles/Project.xml`) and restages them. It skips files with unstaged changes and looks for the formatter at `IDEA_FORMATTER`, the JetBrains Toolbox `idea` script or `idea` on the `PATH`. It never blocks a commit when the formatter is missing or fails. Facts a client needs — versions, features, auth constants, permissions — are generated: add them to the generator that produces the relevant doc, never to a hand-written guide.

## Tests and goldens

`./gradlew test` runs the full suite with the Kover agent attached to every test JVM (CI additionally runs `koverXmlReport koverHtmlReport`). `-PwithoutCoverage=true` runs the tests without the agent, which takes about a tenth less time, and the Kover report tasks then have no coverage to report. The PostgreSQL and Redis tests of the server use Testcontainers, so Docker must be reachable — CI sets `DOCKER_HOST=unix:///var/run/docker.sock`; locally, make sure your Docker daemon is running and `DOCKER_HOST` (if set) points at it. A container that does not start fails every test that needs it, with a message naming the container. `-PwithoutContainers=true` is the deliberate way to run without Docker: the PostgreSQL and Redis tests are then skipped with a reason, and the SQLite cases still run, so a green result says nothing about PostgreSQL or Redis. CI never passes it. Each test JVM ends with one line in the Gradle output, `TEST CONTAINERS: PostgreSQL 15.x (container postgres:15-alpine, port …), Redis 7.x (container …, port …), test JVM <pid>`, which names the servers that JVM ran its tests on and the host ports it reached them at, or `FAILED` or `not used`, and with `-PwithoutContainers=true` says that the PostgreSQL and Redis tests were skipped. A run prints as many of these lines as it has test JVMs.

`:server:test` runs its classes in several test JVMs at once (`maxParallelForks` in `server/build.gradle.kts`). The number is a quarter of the available processors, at most 4, and at most what the memory holds at 3 GB per test JVM once 7 GB are set aside for Gradle, the Kotlin daemon and the containers, so a full run leaves most of a machine free for other work. That gives 4 on a machine with 16 processors and 19 GB or more, and 1 on a GitHub runner with 4 processors, where CI passes `-PtestForks=2`. `-PtestForks=N` sets the number: a higher one makes the run faster as long as cores are free, and `-PtestForks=1` runs every class in one JVM. Gradle deals whole classes to the JVMs in turn, so the classes that share a JVM change with the number of JVMs and with every class that is added, and inside one JVM the classes still run one after the other. A test therefore:

- assumes nothing about the classes that ran before it in its JVM, and leaves no global state behind (static and object mocks, the Koin context, system properties),
- binds no fixed port: a server it starts listens on port 0 or behind a mapped container port,
- writes only to files and directories it created under a unique name (`createTempFile`, `createTempDirectory`, `@TempDir`), never to a fixed name in a shared directory,
- takes its database from `TestDatabase` and its Redis from `TestRedis`,
- waits for the event it expects instead of sleeping a fixed time, and takes the waits it needs from the durations it configured, because several JVMs make every step slower.

The PostgreSQL container (`TestDatabase`) is built for speed, not for keeping data: `fsync`, `synchronous_commit` and `full_page_writes` are off, the data directory is a 2 GB tmpfs, and logins need no password (`POSTGRES_HOST_AUTH_METHOD=trust`). Locally it is reused between runs when `~/.testcontainers.properties` has `testcontainers.reuse.enable=true`, and all test JVMs of a run share it: the first one creates it while it holds a lock on `synara-test-postgres.lock` in the system temp directory, the others find it afterwards. Without reuse, as in CI, every test JVM starts its own container and Testcontainers removes it when the JVM ends. A change to these settings makes Testcontainers start a new container and leaves the previous one running, remove that one with `docker rm -f -v <name>`. Every PostgreSQL case gets its own database, reached through a small connection pool that `TestDatabase.cleanUp()` closes before it drops the database. Databases are created (`STRATEGY FILE_COPY`) and dropped over one administrative connection per test JVM, which is closed when the JVM ends. A test passes its tables to `TestDatabase.connect(dialect, name, tables...)`. On PostgreSQL the schema of each distinct table list is built once per test JVM in a template database (`template_<jvm>_<id>`, found again by the hash of the DDL Exposed generates for the list) and every case gets a copy of it with `CREATE DATABASE ... TEMPLATE`, on SQLite the tables are created in the fresh file. Tables or raw DDL a single test needs on top are created in that test. `TestDatabase.connectMigrated(dialect, name)` and `TestDatabase.getMigratedPostgresDbUrl(name, upTo)` give a database that Flyway migrated from the base, on PostgreSQL again as a copy of a template that is migrated once. A test about applying the base to an empty database starts from `connect` without tables or `getPostgresDbUrl`. Templates accept no connections and are dropped when the test JVM ends.

The Redis container (`TestRedis`, `redis/redis-stack-server:7.4.0-v8`) is a cluster of one node per test JVM, on a mapped host port and never reused. The node announces that host port (`cluster-announce-ip`, `cluster-announce-port`), so the cluster client is sent to an address it can reach, and `TestRedis.host` and `TestRedis.port` are what a test passes to `RedisCacheProvider.Config`. The tests start once the node reports `cluster_state:ok`. `FT.CREATE` scans the existing keys in the background and indexes a hash written before the scan passes it a second time. The first posting stays in the index until its garbage collection, the document frequency of the term then exceeds the number of documents, the default scorer (TFIDF) computes an IDF of 0 and every match scores 0, so the field weights no longer decide the order. A test that creates search indexes therefore calls `TestRedis.awaitSearchIndexes(jedis, indexPrefix)` after `RedisSearchService.initIndex()` and writes its documents only after that: it returns once no index of the prefix reports `indexing`.

In the reports a database case is named `test name(DbDialect) [1] POSTGRES` or `… [2] SQLITE` (`junit.jupiter.params.displayname.default` in `server/src/test/resources/junit-platform.properties`).

JUnit does not run a test method that returns a value, and reports nothing about it. A test written as an expression body (`= runBlocking { … }`) whose last expression has a value, such as `assertThrows` or `assertNotNull`, is such a method, so it gets `: Unit`. `TestMethodReturnTypeTest` fails for every test method of the server test sources that returns a value.

REST route goldens live at `server/src/test/resources/rest/routes.golden` and `routes.openapi.golden`, checked by `RestRouteGoldenTest` (`server/src/test/kotlin/dev/dertyp/routing/rest/RestGoldenSupport.kt`). After a routing change, refresh them with:

```bash
./gradlew :server:test -PupdateRestGolden=true --tests 'dev.dertyp.routing.rest.RestRouteGoldenTest'
```

then review the diff — this is what turns REST routing changes into an intentional, reviewed golden update instead of a silent behavior change.

`python3 scripts/test-times.py` reads the JUnit XML reports of the last run and prints the test times per module, database type and class; `--json <file>` saves them and `--compare <file>` shows the change against such a file, for the classes both have a report for. Gradle keeps only the reports of the last run of a test task, so a run with `--tests` leaves the reports of those classes alone.

`python3 scripts/commit-stats.py` regenerates [STATS.html](STATS.html), an interactive page of commit statistics to open in a browser, from the git history of this repository and of the sibling checkouts `synara-android` and `SynaraComposeMultiplatform`, when they exist next to it; `-o <file>` writes it elsewhere. It only reads from git, and unchanged history gives a byte-identical page. The pages workflow checks out this repository only, so the published page covers it alone.

The build cache and the configuration cache are on (`org.gradle.caching` and `org.gradle.configuration-cache` in `gradle.properties`). A compile or test task whose inputs are unchanged does not run: the output says `UP-TO-DATE`, or `FROM-CACHE` when the result comes from `~/.gradle/caches/build-cache-1`, for example after `clean` or a switch back to a branch that was built before. This holds for tests too, so `./gradlew test` without a change runs no test, and `./gradlew test --rerun` runs them all. The KSP tasks declare the docs they write as outputs, so a generated doc that was edited or reverted by hand is written again by the next `generateDocs`.

The server reports its build time and commit through `BuildInfo`, which reads `build-info.properties` from the classpath. Only `:server:shadowJar` puts that file into the jar, written by `:server:generateBuildInfo` on every jar build with the time of the build and `git rev-parse HEAD` (`unknown` without a git checkout). A server that does not run from the fat jar (`:server:run`, `installDist`, `dev.sh`, tests) reports `dev` for both, so compiling and testing do not depend on the time or the commit. The version stays the compile-time constant `BuildConfig.VERSION`.

Never verify with `./gradlew assemble`; it also builds every `common-rpc` multiplatform target (iOS, tvOS, macOS, Windows, Linux static libraries, see `common-rpc/build.gradle.kts`), which needs toolchains most machines do not have. Verify with the JVM tasks CI runs instead: `./gradlew test`, `./gradlew :server:shadowJar`.

## Generated files

Never hand-edit these; regenerate them instead. CI fails the build if any of them is stale (the `build-artifact` job of `.github/workflows/build.yml` runs the generators together with the jars and then `git diff --exit-code` on their outputs). `./gradlew generateDocs` runs every generator below in one go.

| File | Generated by | Runs |
|---|---|---|
| [RPC_SERVICES.md](RPC_SERVICES.md), [MODELS.md](MODELS.md), [PERMISSIONS.md](PERMISSIONS.md) | `./gradlew :common-rpc:kspCommonMainKotlinMetadata` | As part of `generateDocs`, by the pre-commit hook, and by CI's docs-drift check. |
| [REST_API.md](REST_API.md), and the `register*Rest` functions in `dev.dertyp.routing.rest` | `./gradlew :server:kspKotlin` | Automatically as part of any `:server` compile; also as part of `generateDocs`, by the pre-commit hook, and by CI's docs-drift check. |
| [API_CONSTANTS.md](API_CONSTANTS.md) | `./gradlew :server:generateApiConstantsDocs` | As part of `generateDocs`, by the pre-commit hook, and by CI's docs-drift check. |
| [ENVIRONMENT_VARIABLES.md](ENVIRONMENT_VARIABLES.md), [`example.env`](../example.env) | `./gradlew generateEnvDocs` (root `build.gradle.kts`) | As part of `generateDocs`, by the pre-commit hook, and by CI's docs-drift check. |
| [RELATION_MAP.html](RELATION_MAP.html) | `python3 scripts/relation-map/generate.py` (needs Graphviz) | By hand for the file in `docs/`, not part of `generateDocs` or the build workflow. `.github/workflows/pages.yml` regenerates it on every push to master and publishes it to the `gh-pages` branch. The system, credentials, API surface, pipelines, events, startup and build views are curated data in `scripts/relation-map/curated/`, update them by hand when a flow changes. The workers are read from the `@WorkerTask` annotations, the packages and classes from the Kotlin sources and the database from the base schema. |
| [STATS.html](STATS.html) | `python3 scripts/commit-stats.py` | By hand for the file in `docs/`, not part of `generateDocs` or the build workflow. `.github/workflows/pages.yml` regenerates it on every push to master and publishes it to the `gh-pages` branch. The numbers change with every commit, so regenerate the file in `docs/` when they should be current. |

[index.html](index.html) is written by hand and links both pages. The pages workflow publishes it next to them as the start page and fills in the commit and date of the build.

If a KSP task reports up-to-date but you know its output should change, touch one of its input source files — Gradle otherwise skips it.

## `common-rpc` submodule workflow

1. Make the interface/model change inside `common-rpc/` and commit it there first.
2. Regenerate the docs and goldens affected by the change (see Generated files, and Tests and goldens above).
3. Back in the server repo, stage the updated submodule pointer (`git add common-rpc`) and commit it together with the server-side change that depends on it and the regenerated docs — this combined commit is the "pointer bump".

## Adding an RPC method end to end

1. Declare the method on the service interface in `common-rpc/src/commonMain/kotlin`, documented with `@RpcDoc`/`@RpcParamDoc` (and, for REST, `@RestGet`/`@RestPost`/`@RestPut`/`@RestDelete`, `@RestPath`, `@RestPublic`, `@RestFileResponse` as needed — all in `common-rpc/src/commonMain/kotlin/dev/dertyp/rpc/annotations/Doc.kt`). Gate access with `@RequiresCapability`/`@RequiresAdmin` (`common-rpc/.../data/UserCapability.kt`) if it shouldn't be open to every authenticated user.
2. Implement it in the server. Find the existing `Rpc*Service` pattern for the service you're extending with `grep -rl "class Rpc" server/src/main/kotlin/dev/dertyp/services`.
3. Register the service factory in both `routing/RpcRegistry.kt` (`registerAuthenticatedServices`/`registerPublicServices`) and `routing/RestRegistry.kt` (`registerAuthenticatedRestServices`/`registerPublicRestServices`) if it's a new service; an added method on an already-registered service needs no registry change.
4. Refresh the REST goldens (`-PupdateRestGolden=true`, see above) if the route set changed.
5. Regenerate `RPC_SERVICES.md`/`MODELS.md` and `REST_API.md` (see Generated files).
6. Commit inside the `common-rpc` submodule, then bump its pointer in the server repo (see the submodule workflow above).

## Conventions

- Foreign keys are always Exposed `reference()` columns, never a hand-rolled `uuid`/`varchar` id column mirroring another table's id.
- Schema changes are a pair of generated SQL files, see [Schema changes](#schema-changes); data backfills and one-off fixes are custom migrations (`migrations/custom/`, `@Migration("3.33")` onward) — see [ARCHITECTURE.md](ARCHITECTURE.md#migrations).
- Recurring/periodic work is a `@WorkerTask` worker (`services/schedule/`), never an ad hoc `while`/`delay` loop.
- Every write to a table behind a tracked entity (songs, albums, artists, playlists, collections and the per-user likes, follows and timecode tags) is announced through `EntityEventPublisher` in the same transaction as the write, or classified as bookkeeping in `server/src/test/resources/entity-change/write-sites.txt`. `EntityChangeRecorder` subscribes to these announcements and records the change in that transaction. `EntityChangeCoverageTest` enforces this and fails for a write that is neither.
- A custom migration that changes data clients can read announces the changed entities through `EntityEventPublisher` in the same transaction, like a service does. `EntityChangeRecorder.restartTracking()` is only for the cases that cannot name what they changed, such as a database restore.
- A reaction to an entity event is a subscriber, not a call a service places after its write. The class implements `HookSubscriber`, is bound in Koin with `bind<HookSubscriber>()` and subscribes in `subscribe(hooks)`. `configureHooks()` (`Hooks.kt`) calls every bound subscriber before the database is ready, so `subscribe` and `init` only register and never read data. `HookSubscriberBindingTest` fails for a subscriber class that no production module binds as `HookSubscriber`. Services announce through `EntityEventPublisher` and do not know who listens.
- A subscriber runs in one of two phases. `HookService.inTransaction` registers an `EntityWriteSubscriber` that is called synchronously inside the transaction of the write, and is only for data that must commit or roll back with that write, as `EntityChangeRecorder` does. Everything else subscribes with `HookService.on` and runs asynchronously after the commit, with one event per kind and entity type for the whole transaction. Plugins get the after-commit phase only, through `HookService.forPlugin`, limited to the hook groups they declare (see [PLUGINS.md](PLUGINS.md#hooks)).
- A new `HookEvent` gets its hook group in `HookEvent.hookGroup()`, or null when only the server reacts to it, as for `AlbumsLinkedToMusicBrainz`, and a sample in `PluginHooks`, and a line in [PLUGINS.md](PLUGINS.md#events). `PluginHooksTest` fails for an event without a sample.
- `VersionGroupTrigger` rebuilds the album version groups after album events: after `VERSION_GROUP_REBUILD_QUIET_SECONDS` (1) without a further event, and at the latest `VERSION_GROUP_REBUILD_MAX_WAIT_SECONDS` (5) after the first one that is waiting. Both come from `VersionGroupConfig`, where a quiet period below one second counts as one second and a maximum wait below the quiet period counts as the quiet period. All commits of one operation therefore lead to one rebuild on complete data. Code that changes what the grouping reads without an album event, such as `AlbumService.setMusicBrainzId` filling in the cached release group, calls `VersionGroupTrigger.requestRebuild()`, which joins the same wait. A caller that needs the groups rebuilt before it continues awaits `AlbumService.rebuildVersionGroups()` itself. A rebuild that is still waiting when the server stops is not run. Tests give the trigger shorter waits through `RecordedEntityEvents`.
- REST routes are generated, not hand-written; control them through the `common-rpc` doc annotations, not by editing `routing/rest/`.

## Schema changes

The schema starts from a frozen base per database type, and a schema change is a pair of SQL files, one for PostgreSQL and one for SQLite, generated from the table objects.

1. Change the table object in `server/src/main/kotlin/dev/dertyp/db/`.
2. Run `./gradlew :server:generateMigration -Pname=AddSomething`. It needs Docker, or an existing PostgreSQL given with `-PpostgresUrl`, `-PpostgresUser` and `-PpostgresPassword`.
3. Review both new files in `server/src/main/resources/db/migrations/postgres/` and `.../sqlite/`. Append by hand what Exposed cannot generate, such as search triggers, functions, GIN or expression indexes. Commit both files, even if one of them is empty.
4. A released migration and the base (`B1_109__Base.sql`) are never edited. A correction is a new migration.

The generator writes what Exposed proposes, and three kinds of change need a correction by hand before the files are committed:

- A renamed column is generated as `ADD` of the new column and `DROP COLUMN` of the old one, which deletes its data. Replace both statements by `ALTER TABLE ... RENAME COLUMN ... TO ...` in both files. The generator prints a warning for a table that gains and loses columns in the same run.
- A new `NOT NULL` column without a database default (`default(...)`, not `clientDefault`) fails on PostgreSQL once the table has rows and always on SQLite. The generator prints a warning for it.
- On SQLite, a changed type, default or nullability of an existing column and a new foreign key on an existing table produce no statement, so the SQLite file stays without them and `SchemaDriftTest` cannot see the difference on SQLite. The table rebuild (new table, copy, drop, rename) is written by hand.

`SchemaDriftTest` fails for a table change without a migration, and `MigrationFilesTest` for a file that exists for only one database type or for a Flyway Kotlin class. Data changes stay custom migrations in the package `dev.dertyp.migrations.custom`, which is created again with the next one.

At startup `MigrationBaseCheck` refuses a database that is not at the base (see [ARCHITECTURE.md](ARCHITECTURE.md#migrations)), and the message names the `0.0.1-dev` image to start first, see [Updating from 0.0.1](../README.md#updating-from-001). Backups carry the schema version they were made at, and a restore applies the same rule before it changes anything, so a new migration needs no change to the backup code.

## Dependency updates

`check_updates.py` (repo root) reads `gradle/libs.versions.toml`, checks Maven Central and JitPack for newer compatible versions of each dependency, and prints `key=version` lines for anything outdated:

```bash
python3 check_updates.py
```

Review the output (redirect it to a file such as `updates.txt` if that's easier to work through) and apply the version bumps you want to `gradle/libs.versions.toml` by hand.

## Troubleshooting

- **Build fails with `common-rpc` sources missing**: the repo was cloned without submodules; run `git submodule update --init --recursive`.
- **CI fails on "Verify generated docs are current"**: a change touched RPC interfaces, REST routes, or environment variables without regenerating the corresponding doc; run the generator locally (see Generated files) and commit the diff.
- **PostgreSQL-backed tests fail immediately with a container error**: Testcontainers couldn't reach Docker; make sure Docker is running and `DOCKER_HOST` is unset or points at a working daemon (CI sets it to `unix:///var/run/docker.sock`).
- **`./gradlew assemble` fails**: it also builds every `common-rpc` native target (iOS, tvOS, macOS, Windows, Linux), which needs toolchains most machines do not have; use `./gradlew test` or `./gradlew :server:shadowJar` instead.
