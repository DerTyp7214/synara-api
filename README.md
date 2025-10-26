# Synara API

## Information

 - Api docs (swagger) can be found at `/swagger`
 - Artist delimiter in the music tags should be `;`
 - Only supports `flac` at the moment.
 - Transcoder saves as `opus`
 - Have a look at the `application.yaml` to see which env vars exist.
   - `secondary-tracks` is an optional list of paths

## Running

To run the project, use one of the following tasks:

| Task                    | Description                      |
|-------------------------|----------------------------------|
| `./gradlew :server:run` | Run the server                   |
| `./docker compose up`   | Run using the local docker image |

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```
