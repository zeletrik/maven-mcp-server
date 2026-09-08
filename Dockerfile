# Stage 1: Build in the CI
# Stage 2: Optimizer for extracting layers
FROM public.ecr.aws/docker/library/amazoncorretto:25-alpine AS optimizer

ARG APP_VERSION

WORKDIR /opt/maven-mcp-server

# Copy the JAR file from the CI build stage.
COPY build/libs/*-${APP_VERSION}.jar service.jar

# Extract the application's layers to optimize the final image.
# Managed by Spring Boot
RUN java -Djarmode=tools -jar service.jar extract --destination application
# NOTE: no JDK AOT cache (Project Leyden) is trained here. A Leyden AOT cache bakes in CPU-specific
# compiled code (it records the instruction-set extensions of the machine that trained it). Since
# this image is built on a CI runner and run on arbitrary homelab CPUs, a baked-in cache can crash
# with SIGILL (illegal instruction) on a host whose CPU lacks those extensions. Portability wins
# over the small startup speedup; see the ENTRYPOINT below.

# Stage 3: Final
FROM public.ecr.aws/docker/library/amazoncorretto:25-alpine

ARG APP_VERSION

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

# Liveness probe. Actuator is not on the classpath, and the MCP endpoint only accepts POST, so a GET
# to /mcp answers 405 — which still proves the server is up and speaking HTTP. wget exits 8 on an
# HTTP error response and 4 when the connection is refused, so both 0 and 8 count as alive.
HEALTHCHECK --start-period=40s --interval=30s --timeout=3s --retries=3 \
    CMD wget -qO- http://localhost:8080/mcp >/dev/null 2>&1 || [ $? -eq 8 ]

# Plain launch — no JDK AOT cache, so the JIT compiles for the actual runtime CPU and the image
# runs on any amd64 host. (A build-time Leyden cache trained on the CI runner's CPU can SIGILL on a
# homelab CPU with a narrower instruction set.) spring.aot.enabled is intentionally not set because
# the JAR was not built with Spring's processAot step.
ENTRYPOINT ["java", "-jar", "service.jar"]
