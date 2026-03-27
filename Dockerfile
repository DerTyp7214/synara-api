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
# Build the fat JAR using shadowJar for better native library support
RUN gradle :server:shadowJar --no-daemon

# Stage 3: FFmpeg-Builder
FROM debian:bullseye-slim AS ffmpeg-builder

ENV FFMPEG_VERSION 8.0.1

RUN apt-get update && \
    apt-get install -y --no-install-recommends build-essential pkg-config wget tar zlib1g-dev ca-certificates nasm && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /src
RUN wget https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz && \
    tar xf ffmpeg-${FFMPEG_VERSION}.tar.xz

WORKDIR /src/ffmpeg-${FFMPEG_VERSION}

RUN ./configure \
    --enable-shared \
    --disable-static \
    --enable-pic \
    --enable-avutil \
    --enable-avcodec \
    --enable-avformat \
    --enable-avdevice \
    --enable-swscale \
    --enable-swresample \
    --disable-doc \
    --disable-ffplay \
    --extra-libs="-ldl -lm -lz -lrt"

RUN make -j$(nproc) && make install

# Stage 4: Create the Runtime Image
FROM amazoncorretto:25 AS runtime

ARG APP_USER_ID=1000
ARG APP_GROUP_ID=1000

RUN yum update -y && yum install -y python3.13 python3.13-pip libstdc++ zlib glibc shadow-utils && \
    yum clean all && rm -rf /var/cache/yum

RUN ln -sf /usr/bin/python3.13 /usr/bin/python3
RUN python3 -m pip install --break-system-packages --no-cache-dir tidal-dl-ng syncedlyrics tiddl

COPY --from=ffmpeg-builder /usr/local/lib/libav*.so* /usr/lib/
COPY --from=ffmpeg-builder /usr/local/lib/libsw*.so* /usr/lib/

COPY --from=ffmpeg-builder /usr/local/bin/ffmpeg /usr/bin/
COPY --from=ffmpeg-builder /usr/local/bin/ffprobe /usr/bin/

ENV LD_LIBRARY_PATH="/usr/lib:/usr/local/lib:$LD_LIBRARY_PATH"

RUN groupadd -g $APP_GROUP_ID appgroup && useradd -u $APP_USER_ID -g appgroup -s /bin/bash -m -d /home/appuser appuser

EXPOSE 8080

RUN mkdir /app
RUN chown appuser:appgroup /app

RUN mkdir /data
RUN chown appuser:appgroup /data

RUN mkdir -p /home/appuser/.tiddl
RUN mkdir -p /home/appuser/.config/tidal_dl_ng
RUN chown -R appuser:appgroup /home/appuser

COPY --chown=appuser:appuser docker/tiddl/config.toml /home/appuser/.tiddl/config.toml
COPY --chown=appuser:appuser docker/tdn-config/settings.json /home/appuser/.config/tidal_dl_ng/settings.json
COPY --from=build --chown=appuser:appgroup /home/gradle/src/server/build/libs/*.jar /app/synara-api.jar

USER appuser

ENV AUDIO_TRACKS_PATH="/data/Tidal/Tracks"
ENV AUDIO_ALBUMS_PATH="/data/Tidal/Albums"
ENV AUDIO_PLAYLISTS_PATH="/data/Tidal/Playlists"
ENV AUDIO_TRANSCODE_PATH="/data/Tidal/Transcode"
ENV AUDIO_CUSTOM_PATH="/data/Synara/custom"
ENV DATA_IMAGES_PATH="/data/Tidal/Images"

ENV AUDIO_TRACKS_SECONDARY_PATH="/data/Synara"

ENV HOME=/home/appuser
ENTRYPOINT ["java","--enable-native-access=ALL-UNNAMED","-jar","/app/synara-api.jar"]