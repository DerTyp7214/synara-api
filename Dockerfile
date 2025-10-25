# Stage 1: Cache Gradle dependencies
FROM gradle:latest AS cache
RUN mkdir -p /home/gradle/cache_home
ENV GRADLE_USER_HOME=/home/gradle/cache_home
COPY build.gradle.* gradle.properties /home/gradle/app/
COPY gradle /home/gradle/app/gradle
WORKDIR /home/gradle/app
RUN gradle dependencies --no-daemon

# Stage 2: Build Application
FROM gradle:latest AS build
COPY --from=cache /home/gradle/cache_home /home/gradle/.gradle
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
# Build the fat JAR, Gradle also supports shadow
# and boot JAR by default.
RUN gradle buildFatJar --no-daemon

# Stage 3: Create the Runtime Image
FROM amazoncorretto:25-alpine AS runtime

ARG APP_USER_ID=1000
ARG APP_GROUP_ID=1000

RUN apk update && apk add --no-cache python3 py3-pip shadow ffmpeg
RUN python3 -m pip install --break-system-packages --no-cache-dir tidal-dl-ng

RUN addgroup -S -g $APP_GROUP_ID appgroup && adduser -S -G appgroup -u $APP_USER_ID -h /home/appuser appuser

EXPOSE 8080

RUN mkdir /app
RUN chown appuser:appgroup /app

RUN mkdir /data
RUN chown appuser:appgroup /data

RUN mkdir -p /home/appuser/.config/tidal_dl_ng
RUN chown -R appuser:appgroup /home/appuser

COPY --chown=appuser:appuser docker/tdn-config/settings.json /home/appuser/.config/tidal_dl_ng/settings.json
COPY --from=build --chown=appuser:appgroup /home/gradle/src/server/build/libs/*.jar /app/synara-api.jar

USER appuser

ENV AUDIO_TRACKS_PATH="/data/Tidal/Tracks"
ENV AUDIO_ALBUMS_PATH="/data/Tidal/Albums"
ENV AUDIO_PLAYLISTS_PATH="/data/Tidal/Playlists"
ENV AUDIO_TRANSCODE_PATH="/data/Tidal/Transcode"
ENV DATA_IMAGES_PATH="/data/Tidal/Images"

ENV TDN_TOKEN_PATH="/home/appuser/.config/tidal_dl_ng/token.json"

ENV HOME=/home/appuser
ENTRYPOINT ["java","-jar","/app/synara-api.jar"]
