# Synara Mock Server

The Synara Mock Server is a lightweight, dynamic server designed for development and testing. It automatically generates mock endpoints for all Synara RPC services and provides a REST API with randomly generated dummy data.

## Features

- **Dynamic RPC Generation**: Automatically registers all interfaces found in the service registry as kRPC endpoints.
- **REST API Support**: Dynamically creates REST endpoints based on service methods, supporting `GET`, `POST`, `PUT`, and `DELETE`.
- **Automatic Data Generation**: Uses reflection to generate realistic dummy data for complex data structures, including:
    - Primitives (String, Int, Long, Boolean, etc.)
    - Collections (List, Set)
    - Kotlin Data Classes
    - Enums
    - `Flow` streams
    - `PaginatedResponse` wrappers

## How it Works

The mock server uses the `MockGenerator` to create dynamic proxies for service interfaces. When an endpoint is called, the server identifies the return type of the corresponding method and generates a dummy instance of that type.

### Mock Generation Strategy

- **Strings**: Randomly generated UUID-based strings.
- **Numbers**: Random values within sensible ranges.
- **Data Classes**: Recursively populates all constructor parameters with dummy data.
- **Collections**: Generates small lists (usually 3-5 items) of the requested type.
- **Nullable Types**: Occasionally returns `null` (approx. 10% chance) to test null handling in clients.

## Running the Mock Server

### Using Gradle

You can start the mock server directly using Gradle:

```bash
./gradlew :mock-server:run
```

The server will start on port `8081` by default.

### Using Docker

#### Local Build
The mock server can be built and run locally using the provided `Dockerfile.mock`:

```bash
docker build -t synara-mock-server -f Dockerfile.mock .
docker run -p 8081:8081 synara-mock-server
```

#### Public Image
Pre-built Docker images are available on GitHub Container Registry:

```bash
docker run -p 8081:8081 ghcr.io/dertyp7214/synara-mock:latest-dev
```

Currently, only `-dev` tags are available (e.g., `latest-dev`, `<version>-dev`).

### Explicit Mocks

Services whose responses need to be coherent rather than random are implemented by hand and registered in `explicitMocks` in `Main.kt`. Currently:

- **`IUiService`** (`MockUiService`): serves the server-driven UI fixtures used in [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md) — a `library` slot tile, the `core.importer` page (toolbar, URL form with scanner portal, live log pane), the modal `core.importer.queue` and `core.importer.settings` pages, the `core.importer.card` home card with pin/unpin state kept in memory, `invoke` handlers that validate the importer form, and `dispatchHook` returning one handler for Tidal links and two for Apple Music links. `subscribe` re-emits every two seconds and `subscribeLive("core.importer", "log")` appends a log line every 500 ms so live rendering can be tested.

For explicit mocks the REST endpoints call the real implementation (string query parameters are passed through, other parameters are generated) and return the first element of `Flow` results.

## Authentication Mocking

The mock server includes a `MockAuthPlugin` that simulates authentication.

- **Public Services**: `IAuthService` and `IServerStatsService` are accessible without authentication.
- **Protected Services**: All other services require an `Authorization` header to be present in the request. The content of the header is not validated, but it must be present.

## API Endpoints

### kRPC

The mock server exposes kRPC endpoints at the following paths:

- `/rpc`: Public services.
- `/rpc/auth`: Specifically for `IAuthService`.
- `/rpc/services`: Protected services (requires `Authorization` header).

### REST API

REST endpoints are generated following the pattern: `/{serviceName}/{methodName}`.

- The `serviceName` is derived from the interface name (e.g., `IArtistService` -> `artist`).
- The `methodName` is derived from the function name.
- HTTP methods are inferred from annotations (`RestGet`, `RestPost`, etc.) or function name prefixes (e.g., `get...` -> `GET`, `add...` -> `POST`).

**Example:**
To call `IArtistService.byId(id: PlatformUUID)`, the REST endpoint would be:
`GET http://localhost:8081/artist/byId?id=...`

## Configuration

The mock server can be configured using environment variables:

| Variable | Description | Default |
| :--- | :--- | :--- |
| `PORT` | The port the server listens on. | `8081` |
