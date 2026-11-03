# Quarter Master server image. Build from the repository root:
#   docker build -f Server/Dockerfile --build-arg QM_COMMIT=$(git rev-parse HEAD) -t quartermaster-server .
# or use Server/docker-compose.yml.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Only what the server build needs; -PserverOnly leaves the Android app out, so no Android SDK.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY Shared Shared
COPY Server Server
# A Windows checkout may have given gradlew CRLF line endings.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew \
 && ./gradlew -PserverOnly --no-daemon --console=plain :server:installDist

FROM eclipse-temurin:21-jre
ARG QM_COMMIT=dev
ENV QM_COMMIT=${QM_COMMIT} \
    QM_DATA_DIR=/data \
    QM_PORT=47821
RUN useradd --system --home /data --shell /usr/sbin/nologin quartermaster \
 && mkdir -p /data && chown quartermaster /data
COPY --from=build /src/Server/build/install/quartermaster-server /opt/quartermaster
USER quartermaster
VOLUME /data
# HTTP API and admin page (TCP), LAN discovery (UDP).
EXPOSE 47821/tcp 47821/udp
ENTRYPOINT ["/opt/quartermaster/bin/quartermaster-server"]
