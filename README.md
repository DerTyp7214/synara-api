# ktor-sample

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:

- [Ktor Documentation](https://ktor.io/docs/home.html)
- [Ktor GitHub page](https://github.com/ktorio/ktor)
- The [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). You'll need to [request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up) to join.

## Features

Here's a list of features included in this project:

| Name                                                                   | Description                                                                        |
| ------------------------------------------------------------------------|------------------------------------------------------------------------------------ |
| [CORS](https://start.ktor.io/p/cors)                                   | Enables Cross-Origin Resource Sharing (CORS)                                       |
| [Routing](https://start.ktor.io/p/routing)                             | Provides a structured routing DSL                                                  |
| [Simple Cache](https://start.ktor.io/p/simple-cache)                   | Provides API for cache management                                                  |
| [Simple Memory Cache](https://start.ktor.io/p/simple-memory-cache)     | Provides memory cache for Simple Cache plugin                                      |
| [OpenAPI](https://start.ktor.io/p/openapi)                             | Serves OpenAPI documentation                                                       |
| [Authentication](https://start.ktor.io/p/auth)                         | Provides extension point for handling the Authorization header                     |
| [Authentication JWT](https://start.ktor.io/p/auth-jwt)                 | Handles JSON Web Token (JWT) bearer authentication scheme                          |
| [Server-Sent Events (SSE)](https://start.ktor.io/p/sse)                | Support for server push events                                                     |
| [Static Content](https://start.ktor.io/p/static-content)               | Serves static files from defined locations                                         |
| [Status Pages](https://start.ktor.io/p/status-pages)                   | Provides exception handling for routes                                             |
| [Webjars](https://start.ktor.io/p/webjars)                             | Bundles static assets into your built JAR file                                     |
| [Call Logging](https://start.ktor.io/p/call-logging)                   | Logs client requests                                                               |
| [KHealth](https://start.ktor.io/p/khealth)                             | A simple and customizable health plugin                                            |
| [Content Negotiation](https://start.ktor.io/p/content-negotiation)     | Provides automatic content conversion according to Content-Type and Accept headers |
| [GSON](https://start.ktor.io/p/ktor-gson)                              | Handles JSON serialization using GSON library                                      |
| [HTML DSL](https://start.ktor.io/p/html-dsl)                           | Generates HTML from Kotlin DSL                                                     |
| [kotlinx.serialization](https://start.ktor.io/p/kotlinx-serialization) | Handles JSON serialization using kotlinx.serialization library                     |
| [Exposed](https://start.ktor.io/p/exposed)                             | Adds Exposed database to your application                                          |
| [Postgres](https://start.ktor.io/p/postgres)                           | Adds Postgres database to your application                                         |
| [kotlinx.rpc](https://start.ktor.io/p/kotlinx-rpc)                     | Adds remote procedure call (RPC) routing                                           |

## Structure

This project includes the following modules:

| Path             | Description                                             |
| ------------------|--------------------------------------------------------- |
| [server](server) | A runnable Ktor server implementation                   |
| [core](core)     | Domain objects and interfaces                           |
| [client](client) | Extensions for making requests to the server using Ktor |

## Building

To build the project, use one of the following tasks:

| Task                                            | Description                                                          |
| -------------------------------------------------|---------------------------------------------------------------------- |
| `./gradlew build`                               | Build everything                                                     |
| `./gradlew :server:buildFatJar`                 | Build an executable JAR of the server with all dependencies included |
| `./gradlew :server:buildImage`                  | Build the docker image to use with the fat JAR                       |
| `./gradlew :server:publishImageToLocalRegistry` | Publish the docker image locally                                     |

## Running

To run the project, use one of the following tasks:

| Task                          | Description                      |
| -------------------------------|---------------------------------- |
| `./gradlew :server:run`       | Run the server                   |
| `./gradlew :server:runDocker` | Run using the local docker image |

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

