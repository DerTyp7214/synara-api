MODULES = [
    ("server", "server/src/main/kotlin", "apps", "The Synara API server: kRPC, REST, Subsonic, MCP, radio and the workers.", "server/build.gradle.kts:1"),
    ("proxy", "proxy/src", "apps", "Standalone relay that client kRPC connections reach a server through.", "proxy/build.gradle.kts:1"),
    ("credential-server", "credential-server/src", "apps", "Standalone broker for third-party credentials.", "credential-server/build.gradle.kts:1"),
    ("listen-backup", "listen-backup/src", "apps", "Standalone receiver that keeps a copy of the listen history.", "listen-backup/build.gradle.kts:1"),
    ("mock-server", "mock-server/src", "apps", "Mock of the API with dummy data for client development.", "mock-server/build.gradle.kts:1"),
    ("common-rpc", "common-rpc/src", "libs", "Multiplatform RPC interfaces and models shared with the clients. A git submodule.", "common-rpc/build.gradle.kts:1"),
    ("plugin-api", "plugin-api/src", "libs", "The API that server plugins compile against. It re-exports common-credentials.", "plugin-api/build.gradle.kts:1"),
    ("common-proxy", "common-proxy/src", "libs", "The frame protocol between server and proxy.", "common-proxy/build.gradle.kts:1"),
    ("common-credentials", "common-credentials/src", "libs", "The protocol and models of the credential server.", "common-credentials/build.gradle.kts:1"),
    ("common-listen-backup", "common-listen-backup/src", "libs", "The protocol between server and listen-backup.", "common-listen-backup/build.gradle.kts:1"),
    ("common-rpc:compiler", "common-rpc/compiler/src", "ksp", "RpcProcessor. Reads the @Rpc interfaces and writes dev.dertyp.rpc.NativeDispatchers for the native clients.", "common-rpc/compiler/src/main/kotlin/dev/dertyp/rpc/compiler/RpcProcessor.kt:17"),
    ("common-rpc:doc-compiler", "common-rpc/doc-compiler/src", "ksp", "DocProcessor. Reads @RpcDoc services and @ModelDoc models with their parameter, field and permission annotations and writes the API reference.", "common-rpc/doc-compiler/src/main/kotlin/dev/dertyp/rpc/doc/DocProcessor.kt:39"),
    ("common-rpc:rest-compiler", "common-rpc/rest-compiler/src", "ksp", "RestProcessor. Finds @Rpc interfaces in source, in the packages named by rest.packages and in rest.interfaces, derives routes from the function names and the @Rest annotations and emits the registration code, a route manifest and the REST reference.", "common-rpc/rest-compiler/src/main/kotlin/dev/dertyp/rpc/rest/RestProcessor.kt:45"),
]

DEPENDENCIES = [
    ("server", "common-rpc", "implementation", "implements the RPC interfaces and returns the shared models", "server/build.gradle.kts:165"),
    ("server", "common-proxy", "implementation", "speaks the proxy frame protocol in ReverseProxyService", "server/build.gradle.kts:166"),
    ("server", "common-listen-backup", "implementation", "sends listen batches in the receiver's protocol", "server/build.gradle.kts:167"),
    ("server", "common-credentials", "implementation", "talks to the credential server as consumer and admin", "server/build.gradle.kts:168"),
    ("server", "plugin-api", "implementation", "hosts the plugins and ships the built-in ones", "server/build.gradle.kts:169"),
    ("plugin-api", "common-rpc", "implementation", "plugins work with the shared models", "plugin-api/build.gradle.kts:12"),
    ("plugin-api", "common-credentials", "api", "exposes the credential types to plugins", "plugin-api/build.gradle.kts:13"),
    ("mock-server", "common-rpc", "implementation", "mocks every interface of the generated service registry", "mock-server/build.gradle.kts:20"),
    ("proxy", "common-proxy", "implementation", "the frame protocol", "proxy/build.gradle.kts:20"),
    ("listen-backup", "common-listen-backup", "implementation", "the receiver protocol", "listen-backup/build.gradle.kts:20"),
    ("credential-server", "common-credentials", "implementation", "the credential protocol and models", "credential-server/build.gradle.kts:20"),
    ("common-rpc:doc-compiler", "common-rpc", "implementation", "reads the documentation annotations declared in common-rpc", "common-rpc/doc-compiler/build.gradle.kts:7"),
]

KSP = [
    ("common-rpc", "common-rpc:compiler", "kspJvm and kspCommonMainMetadata", "common-rpc/build.gradle.kts:121"),
    ("common-rpc", "common-rpc:doc-compiler", "kspCommonMainMetadata", "common-rpc/build.gradle.kts:123"),
    ("server", "common-rpc:doc-compiler", "ksp", "server/build.gradle.kts:84"),
    ("server", "common-rpc:rest-compiler", "ksp", "server/build.gradle.kts:85"),
]

CONVENTION_USERS = ["server", "proxy", "credential-server", "listen-backup", "mock-server", "plugin-api", "common-proxy", "common-credentials", "common-listen-backup"]

GENERATED = [
    ("rpc-services-md", "docs/RPC_SERVICES.md", "RPC reference"),
    ("models-md", "docs/MODELS.md", "model reference"),
    ("permissions-md", "docs/PERMISSIONS.md", "permission reference"),
    ("rest-api-md", "docs/REST_API.md", "REST reference"),
    ("rest-code", "register<Service>Rest", "generated REST routes"),
    ("native-dispatchers", "NativeDispatchers", "generated native dispatchers"),
    ("api-constants-md", "docs/API_CONSTANTS.md", "versions and features"),
    ("env-docs", "ENVIRONMENT_VARIABLES.md", "and example.env"),
    ("migration-sql", "V<version>__<Name>.sql", "one file per database type"),
    ("build-config", "BuildConfig", "four build constants"),
    ("relation-map", "docs/RELATION_MAP.html", "this page"),
]

IMAGES = [
    ("image-server", "synara", "Dockerfile.nobuild", "server", "The server image CI publishes. It copies the server fat jar and installs the command-line tools.", "Dockerfile.nobuild:84"),
    ("image-proxy", "synara-proxy", "Dockerfile.proxy", "proxy", "Copies the proxy fat jar.", "Dockerfile.proxy:4"),
    ("image-mock", "synara-mock", "Dockerfile.mock", "mock-server", "Copies the mock-server fat jar.", "Dockerfile.mock:5"),
    ("image-listen-backup", "synara-listen-backup", "Dockerfile.listen-backup", "listen-backup", "Copies the listen-backup fat jar.", "Dockerfile.listen-backup:5"),
    ("image-credentials", "synara-credentials", "Dockerfile.credentials", "credential-server", "Copies the credential-server fat jar.", "Dockerfile.credentials:5"),
]

SIDECARS = [
    ("image-transcriber", "synara-transcriber", "transcriber/Dockerfile", "Python FastAPI service with WhisperX. Built from the transcriber directory, no Gradle module."),
    ("image-audio-embed", "synara-audio-embed", "audio-embed/Dockerfile", "Python FastAPI service with Essentia MusiCNN. Built from the audio-embed directory, no Gradle module."),
    ("image-recsys", "synara-recsys", "recsys/Dockerfile", "Python training loop. Built from the recsys directory, no Gradle module."),
]

GROUPS = [
    {"id": "ci", "label": "CI workflow", "column": 0, "desc": ".github/workflows/build.yml"},
    {"id": "images", "label": "Docker images", "column": 1, "desc": "Published to ghcr.io/dertyp7214."},
    {"id": "apps", "label": "Applications", "column": 2, "desc": "Gradle modules that build a fat jar."},
    {"id": "libs", "label": "Shared libraries", "column": 3},
    {"id": "ksp", "label": "KSP processors", "column": 4, "desc": "Modules under common-rpc that run at build time."},
    {"id": "tasks", "label": "Generator tasks", "column": 5},
    {"id": "generated", "label": "Generated files", "column": 6, "desc": "Never edited by hand."},
    {"id": "infra", "label": "Build infrastructure", "column": 4},
]

STYLES = [
    {"kind": "depends", "dash": "", "label": "compiles against"},
    {"kind": "generates", "dash": "dash", "label": "runs or generates at build time"},
    {"kind": "packages", "dash": "dot", "label": "builds, packages or checks"},
]


def node_id(module):
    return "m-" + module.replace(":", "-")


def count_sources(root, relative):
    base = root / relative
    files = 0
    lines = 0
    if not base.is_dir():
        return files, lines
    for path in sorted(base.rglob("*.kt")):
        directory = str(path.parent)
        inner = directory.replace(str(base), "")
        if "/build" in directory.replace(str(root), "") or "Test" in inner or "/test/" in directory + "/":
            continue
        files += 1
        lines += path.read_text(errors="replace").count("\n")
    return files, lines


def build(root):
    nodes = []
    edges = {}

    def link(source, target, kind, label, flow):
        edge = edges.setdefault((source, target, kind), {"from": source, "to": target, "label": label, "kind": kind, "flows": []})
        edge["flows"].append(flow)

    for module, relative, group, desc, src in MODULES:
        files, lines = count_sources(root, relative)
        node = {
            "id": node_id(module), "label": ":" + module, "sub": f"{files} files, {lines:,} lines", "group": group, "desc": desc,
            "meta": [["Kotlin files", str(files)], ["Lines", f"{lines:,}"], ["Sources", relative]], "src": src,
        }
        if module == "common-rpc":
            node["meta"] += [
                ["Git submodule", "synara-common-rpc"],
                ["Targets", "jvm, android when an SDK is configured, iosX64, iosArm64, iosSimulatorArm64, macosArm64, tvosArm64, tvosSimulatorArm64, mingwX64, linuxX64"],
            ]
        nodes.append(node)

    for source, target, configuration, reason, src in DEPENDENCIES:
        link(node_id(source), node_id(target), "depends", configuration, {
            "title": configuration + " dependency", "transport": "Gradle project dependency", "carries": reason, "src": src,
        })
    for source, target, configuration, src in KSP:
        link(node_id(source), node_id(target), "generates", "ksp", {
            "title": "KSP processor", "transport": "Gradle configuration " + configuration,
            "carries": "the processor runs over the sources of :" + source + " when it compiles", "src": src,
        })

    nodes += [
        {"id": "build-logic", "label": "build-logic", "sub": "synara.kotlin-jvm", "group": "infra",
         "desc": "Included build with the convention plugin synara.kotlin-jvm: JVM toolchain 25 and the test JVM arguments.",
         "meta": [["Applied by", ", ".join(":" + m for m in CONVENTION_USERS)]], "src": "build-logic/src/main/kotlin/synara.kotlin-jvm.gradle.kts:1"},
        {"id": "submodule", "label": "synara-common-rpc", "sub": "git submodule", "group": "infra",
         "desc": "The repository behind common-rpc. An interface change is a commit in that repository plus a pointer bump here.",
         "meta": [["Path", "common-rpc"]], "src": ".gitmodules:1"},
        {"id": "pre-commit", "label": "pre-commit hook", "sub": ".githooks/pre-commit", "group": "infra",
         "desc": "Formats Kotlin and regenerates the documentation when a commit touches the paths that feed it. Installed by the installGitHooks task.",
         "meta": [], "src": ".githooks/pre-commit:1"},
    ]
    for module in CONVENTION_USERS:
        link(node_id(module), "build-logic", "generates", "plugin", {
            "title": "Convention plugin", "transport": "plugins { id(\"synara.kotlin-jvm\") }", "carries": "toolchain and test settings", "src": module + "/build.gradle.kts:" + ("6" if module == "server" else "2"),
        })
    link(node_id("common-rpc"), "submodule", "packages", "is a checkout of", {
        "title": "Git submodule", "transport": "git submodule", "carries": "the common-rpc directory is a checkout of synara-common-rpc", "src": ".gitmodules:1",
    })

    for generated_id, label, sub in GENERATED:
        nodes.append({"id": generated_id, "label": label, "sub": sub, "group": "generated", "desc": "Generated. Regenerate it with the task or processor linked to it.", "meta": []})

    outputs = [
        ("common-rpc:compiler", "native-dispatchers", "NativeDispatchers", "Kotlin dispatchers that let native clients call the @Rpc interfaces", "common-rpc/compiler/src/main/kotlin/dev/dertyp/rpc/compiler/RpcProcessor.kt:17"),
        ("common-rpc:doc-compiler", "rpc-services-md", "RPC reference", "one section per @RpcDoc service with its methods, parameters and permissions", "common-rpc/doc-compiler/src/main/kotlin/dev/dertyp/rpc/doc/DocProcessor.kt:101"),
        ("common-rpc:doc-compiler", "models-md", "Model reference", "one section per @ModelDoc model with its fields and wire names", "common-rpc/doc-compiler/src/main/kotlin/dev/dertyp/rpc/doc/DocProcessor.kt:72"),
        ("common-rpc:doc-compiler", "permissions-md", "Permission reference", "the methods that need admin or a capability", "common-rpc/doc-compiler/src/main/kotlin/dev/dertyp/rpc/doc/DocProcessor.kt:180"),
        ("common-rpc:rest-compiler", "rest-code", "REST registration code", "one register<Service>Rest function per @Rpc interface, written with KotlinPoet into the package named by rest.package", "common-rpc/rest-compiler/src/main/kotlin/dev/dertyp/rpc/rest/KotlinEmitter.kt:62"),
        ("common-rpc:rest-compiler", "rest-api-md", "REST reference", "every generated route with verb, path and parameters", "common-rpc/rest-compiler/src/main/kotlin/dev/dertyp/rpc/rest/RestDocEmitter.kt:47"),
    ]
    for module, target, title, carries, src in outputs:
        link(node_id(module), target, "generates", "writes", {"title": title, "transport": "KSP output", "carries": carries, "src": src})

    nodes += [
        {"id": "task-generate-docs", "label": "generateDocs", "sub": "root task", "group": "tasks",
         "desc": "Aggregates the four documentation tasks: :common-rpc:kspCommonMainKotlinMetadata, :server:kspKotlin, :server:generateApiConstantsDocs and generateEnvDocs.",
         "meta": [], "src": "build.gradle.kts:17"},
        {"id": "task-env-docs", "label": "generateEnvDocs", "sub": "root task", "group": "tasks",
         "desc": "Reads the application.yaml files of server, proxy and credential-server and Dockerfile.nobuild and writes example.env and docs/ENVIRONMENT_VARIABLES.md.",
         "meta": [], "src": "build.gradle.kts:29"},
        {"id": "task-api-constants", "label": "generateApiConstantsDocs", "sub": ":server task", "group": "tasks",
         "desc": "Runs dev.dertyp.docs.ApiConstantsDocsKt on the main classpath and writes docs/API_CONSTANTS.md. Depends on the common-rpc KSP task.",
         "meta": [], "src": "server/build.gradle.kts:37"},
        {"id": "task-migration", "label": "generateMigration", "sub": ":server task", "group": "tasks",
         "desc": "Runs dev.dertyp.migrations.MigrationGeneratorKt on the test classpath and writes the next schema migration as one SQL file per database type from what Exposed still needs. Usage: -Pname=AddSomething.",
         "meta": [], "src": "server/build.gradle.kts:48"},
        {"id": "task-relation-map", "label": "relation-map/generate.py", "sub": "Python, by hand", "group": "tasks",
         "desc": "python3 scripts/relation-map/generate.py. Needs Graphviz. Not part of generateDocs and not run by CI.",
         "meta": [], "src": "scripts/relation-map/generate.py:1"},
    ]
    task_links = [
        ("task-generate-docs", "task-env-docs", "generates", "runs", "Aggregate", "dependsOn", "generateEnvDocs", "build.gradle.kts:21"),
        ("task-generate-docs", "task-api-constants", "generates", "runs", "Aggregate", "dependsOn", ":server:generateApiConstantsDocs", "build.gradle.kts:21"),
        ("task-generate-docs", node_id("common-rpc:doc-compiler"), "generates", "runs", "Aggregate", "dependsOn :common-rpc:kspCommonMainKotlinMetadata and :server:kspKotlin", "the KSP runs that write the RPC, model and permission references", "build.gradle.kts:21"),
        ("task-generate-docs", node_id("common-rpc:rest-compiler"), "generates", "runs", "Aggregate", "dependsOn :server:kspKotlin", "the KSP run that writes the REST reference", "build.gradle.kts:21"),
        ("task-env-docs", "env-docs", "generates", "writes", "Environment docs", "Gradle task", "example.env and docs/ENVIRONMENT_VARIABLES.md from the application.yaml files and Dockerfile.nobuild", "build.gradle.kts:39"),
        ("task-api-constants", "api-constants-md", "generates", "writes", "API constants", "JavaExec", "version, feature and authentication constants read from the server sources", "server/build.gradle.kts:37"),
        ("task-migration", "migration-sql", "generates", "writes", "Schema migration", "JavaExec on the test classpath", "the statements Exposed still needs, for PostgreSQL and SQLite", "server/build.gradle.kts:48"),
        ("task-relation-map", "relation-map", "generates", "writes", "Relation map", "python3 scripts/relation-map/generate.py", "this page, from the sources, the base schema and the curated modules", "scripts/relation-map/generate.py:1"),
        (node_id("server"), "build-config", "generates", "generates", "BuildConfig", "buildConfig block", "VERSION, APP_NAME, BUILD_TIME and GIT_HASH as constants for the server", "server/build.gradle.kts:220"),
        (node_id("server"), "task-api-constants", "generates", "declares", "Task owner", "tasks.register in server/build.gradle.kts", "the task runs code from the server's main source set", "server/build.gradle.kts:37"),
        (node_id("server"), "task-migration", "generates", "declares", "Task owner", "tasks.register in server/build.gradle.kts", "the task runs code from the server's test source set", "server/build.gradle.kts:48"),
        ("pre-commit", "task-generate-docs", "packages", "runs", "Docs on commit", "git hook", "runs generateDocs and stages the seven generated files when the commit touches common-rpc, the server or plugin-api main sources, the proxy application.yaml, Dockerfile.nobuild, build.gradle.kts or server/build.gradle.kts", ".githooks/pre-commit:34"),
    ]
    for source, target, kind, label, title, transport, carries, src in task_links:
        link(source, target, kind, label, {"title": title, "transport": transport, "carries": carries, "src": src})

    for image_id, repo, dockerfile, module, desc, src in IMAGES:
        nodes.append({"id": image_id, "label": repo, "sub": dockerfile, "group": "images", "desc": desc, "meta": [["Registry", "ghcr.io/dertyp7214/" + repo]], "src": src})
        link(image_id, node_id(module), "packages", "packages", {
            "title": "Fat jar into the image", "transport": "COPY *.jar", "carries": "the shadow jar of :" + module + " built by the CI artifact job", "src": src,
        })
    for image_id, repo, dockerfile, desc in SIDECARS:
        nodes.append({"id": image_id, "label": repo, "sub": dockerfile, "group": "images", "desc": desc, "meta": [["Registry", "ghcr.io/dertyp7214/" + repo]], "src": dockerfile + ":1"})
    nodes.append({
        "id": "image-server-full", "label": "Dockerfile", "sub": "builds from source", "group": "images",
        "desc": "The multi-stage Dockerfile that runs Gradle inside the build and copies server/build/libs/*.jar. It is not in the CI image matrix.",
        "meta": [], "src": "Dockerfile:103",
    })
    link("image-server-full", node_id("server"), "packages", "builds", {
        "title": "Build inside Docker", "transport": "gradle build stage, then COPY --from=build", "carries": "the server jar built in the image", "src": "Dockerfile:103",
    })

    workflow = ".github/workflows/build.yml"
    nodes += [
        {"id": "ci-test", "label": "Tests and coverage", "sub": "gradlew test kover", "group": "ci",
         "desc": "./gradlew test koverXmlReport koverHtmlReport over all modules, with the test report and the coverage summary published.", "meta": [], "src": workflow + ":57"},
        {"id": "ci-docs", "label": "Generated docs are current", "sub": "generateDocs + git diff", "group": "ci",
         "desc": "Runs generateDocs and fails when git diff shows a change in docs/RPC_SERVICES.md, MODELS.md, REST_API.md, PERMISSIONS.md, API_CONSTANTS.md, ENVIRONMENT_VARIABLES.md or example.env.", "meta": [], "src": workflow + ":59"},
        {"id": "ci-jars", "label": "Fat jars", "sub": "five shadowJar tasks", "group": "ci",
         "desc": "Builds :server:shadowJar, :proxy:shadowJar, :mock-server:shadowJar, :listen-backup:shadowJar and :credential-server:shadowJar and uploads each as an artifact.", "meta": [], "src": workflow + ":124"},
        {"id": "ci-images", "label": "Image matrix", "sub": "eight images", "group": "ci",
         "desc": "Builds and pushes one image per matrix entry for linux/amd64 and linux/arm64 unless the entry says otherwise. The five jar images download their fat jar artifact first.", "meta": [], "src": workflow + ":177"},
        {"id": "ci-release", "label": "GitHub release", "sub": "jars attached", "group": "ci",
         "desc": "Creates the versioned release and updates the latest release with the five fat jars.", "meta": [], "src": workflow + ":318"},
    ]
    link("ci-docs", "task-generate-docs", "packages", "runs", {
        "title": "Docs check", "transport": "./gradlew generateDocs, then git diff --exit-code",
        "carries": "the seven generated files must come out unchanged", "trigger": "every CI run", "src": workflow + ":61",
    })
    for module in ("server", "proxy", "mock-server", "listen-backup", "credential-server"):
        link("ci-jars", node_id(module), "packages", "shadowJar", {
            "title": ":" + module + ":shadowJar", "transport": "Gradle", "carries": "the fat jar, uploaded as a workflow artifact", "src": workflow + ":124",
        })
        link("ci-test", node_id(module), "packages", "tests", {
            "title": "Tests of :" + module, "transport": "./gradlew test koverXmlReport koverHtmlReport", "carries": "the test task of every module that has tests runs in this one invocation", "src": workflow + ":57",
        })
    for image_id, repo, dockerfile, _, _, _ in IMAGES:
        link("ci-images", image_id, "packages", "builds", {
            "title": repo, "transport": "docker build and push", "carries": dockerfile + " with the repository root as context, where the fat jar artifact was downloaded", "src": workflow + ":177",
        })
    for image_id, repo, dockerfile, _ in SIDECARS:
        link("ci-images", image_id, "packages", "builds", {
            "title": repo, "transport": "docker build and push", "carries": dockerfile + " with its own directory as context", "src": workflow + ":177",
        })
    link("ci-images", "ci-jars", "packages", "needs", {
        "title": "Jar artifacts", "transport": "download-artifact", "carries": "pulls the fat jar of the matrix entry before the image build", "src": workflow + ":231",
    })
    link("ci-release", "ci-jars", "packages", "needs", {
        "title": "Release assets", "transport": "download-artifact", "carries": "pulls the five fat jars and attaches them to the release", "src": workflow + ":288",
    })

    return {
        "id": "modules",
        "tab": "Build",
        "noun": "build element",
        "blurb": "The Gradle modules, what each compiles against and why, what the KSP processors and generator tasks write, which images are built from what, and what CI checks. Solid arrows point from a module to the module it depends on.",
        "out": "Depends on, runs or writes",
        "inn": "Used, run or written by",
        "layout": {"kind": "columns", "bundle": True},
        "groups": GROUPS,
        "styles": STYLES,
        "nodes": nodes,
        "edges": [edges[key] for key in sorted(edges)],
        "notes": [
            {"title": "Docs are generated, and CI checks them", "text": "RPC_SERVICES.md, MODELS.md, PERMISSIONS.md and REST_API.md come from KSP processors, API_CONSTANTS.md and the environment docs from Gradle tasks. CI runs generateDocs and fails on a diff.", "src": workflow + ":59"},
            {"title": "The doc processor runs on two modules", "text": "DocProcessor is a KSP processor of :common-rpc and of :server. It returns without writing when the module has no @RpcDoc or @ModelDoc symbol in its sources. The server main sources carry none, so the three files come from the :common-rpc run. Each run that finds symbols rewrites the files from its own symbols alone.", "src": "common-rpc/doc-compiler/src/main/kotlin/dev/dertyp/rpc/doc/DocProcessor.kt:48"},
            {"title": "The migration generator lives in the test sources", "text": "generateMigration runs dev.dertyp.migrations.MigrationGeneratorKt on the test runtime classpath.", "src": "server/build.gradle.kts:48"},
            {"title": "Two server Dockerfiles", "text": "CI publishes the server image from Dockerfile.nobuild, which copies a prebuilt fat jar. Dockerfile builds the jar inside the image and is not in the CI matrix.", "src": workflow + ":181"},
            {"title": "The relation map is regenerated by hand", "text": "docs/RELATION_MAP.html is not part of generateDocs and is not in the CI diff check.", "src": "scripts/relation-map/generate.py:1"},
        ],
    }
