# Stage 1: Build in the CI
# Stage 2: Optimizer for extracting layers
#
# Both stages pin the base image by DIGEST, not just by tag. A floating tag such as `25-alpine`
# lets a cached or stale pull ship whatever JDK it last saw; the digest makes every fresh build
# use exactly this JDK. This one is Corretto 25.0.5.1.2 (2026-11-17), the first Corretto 25 build
# fixed for CVE-2026-114938 ("Java4Shell", RCE in the AVX-512 Base64 decoder intrinsic). Bump
# the tag and digest together, and never to a build older than that.
FROM public.ecr.aws/docker/library/amazoncorretto:25.0.5-alpine3.24@sha256:1124f698f69d2185828dbd1a1ebeb9aee21884ac64bed7af3deeafd20b21a9dc AS optimizer

ARG APP_VERSION

WORKDIR /opt/maven-mcp-server

# Copy the JAR file from the CI build stage.
COPY build/libs/*-${APP_VERSION}.jar service.jar

# Extract the application's layers to optimize the final image.
# Managed by Spring Boot
RUN java -Djarmode=tools -jar service.jar extract --destination application
# NOTE: no JDK AOT cache (Project Leyden) is trained here. A Leyden AOT cache bakes in CPU-specific
# compiled code (it records the instruction-set extensions of the machine that trained it). Since
# this image is built on a CI runner and run on arbitrary host CPUs, a baked-in cache can crash
# with SIGILL (illegal instruction) on a host whose CPU lacks those extensions. Portability wins
# over the small startup speedup; see the ENTRYPOINT below.

# Stage 3: Final (same pinned, Java4Shell-patched base as the optimizer stage)
FROM public.ecr.aws/docker/library/amazoncorretto:25.0.5-alpine3.24@sha256:1124f698f69d2185828dbd1a1ebeb9aee21884ac64bed7af3deeafd20b21a9dc

ARG APP_VERSION
# Promote the build arg to a runtime variable. application.yaml reads ${APP_VERSION:0.0.0-dev} for
# the version reported in the MCP handshake, and an ARG is visible only during the build — without
# this line every image, whatever its tag, introduces itself as 0.0.0-dev.
ENV APP_VERSION=${APP_VERSION}

# The app sets no server.port, so it serves on the Spring Boot default.
EXPOSE 8080

# Set the working directory for the final application.
WORKDIR /opt/maven-mcp-server

# Copy the cache from the optimizer stage.
COPY --from=optimizer /opt/maven-mcp-server/application ./

# Run as a non-root user. The server keeps no state on disk, so no writable volume is needed.
RUN addgroup -S app && adduser -S -G app app \
    && chown -R app:app /opt/maven-mcp-server
USER app

# Liveness probe against Actuator: /actuator/health answers 200 when UP and 503 otherwise, and
# BusyBox wget exits non-zero on any non-2xx as well as on a refused connection, so a plain GET is
# a correct probe here. (A GET against /mcp is NOT: BusyBox wget collapses the 405 that endpoint
# returns, a 404, and connection-refused all to exit 1, so it cannot tell "up" from "down".)
HEALTHCHECK --start-period=40s --interval=30s --timeout=3s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health >/dev/null 2>&1

# Plain launch — no JDK AOT cache, so the JIT compiles for the actual runtime CPU and the image
# runs on any host. (A build-time Leyden cache trained on the CI runner's CPU can SIGILL on a
# host CPU with a narrower instruction set.) spring.aot.enabled is intentionally not set because
# the JAR was not built with Spring's processAot step.
ENTRYPOINT ["java", "-jar", "service.jar"]
