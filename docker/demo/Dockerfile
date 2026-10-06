# ============================================================
# AgriTrace Demo Dockerfile — Multi-stage build
# Stage 1: Maven build (produces AgriTrace.war)
# Stage 2: Tomcat 10.1 runtime image
# No secrets, no private keys, no local paths embedded.
# ============================================================

# ---- Stage 1: Build ----------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

# Copy Maven wrapper / pom first for layer caching
COPY pom.xml ./
COPY src ./src

# Install Maven
RUN apt-get update -qq && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*

# Build WAR (skip tests — unit tests require network, run separately)
RUN mvn -q -B clean package -DskipTests

# ---- Stage 2: Runtime --------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS runtime

LABEL org.opencontainers.image.title="AgriTrace Demo Node"
LABEL org.opencontainers.image.description="Mini-AgriTrace 3-node demo — single Tomcat node"

# Install Tomcat 10.1 and tools
ARG TOMCAT_VERSION=10.1.39
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends \
        curl \
        openssl \
        maven \
        wget \
    && rm -rf /var/lib/apt/lists/*

# Download and install Tomcat
RUN wget -q "https://archive.apache.org/dist/tomcat/tomcat-10/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" \
    -O /tmp/tomcat.tar.gz \
    && tar -xzf /tmp/tomcat.tar.gz -C /opt \
    && mv /opt/apache-tomcat-${TOMCAT_VERSION} /opt/tomcat \
    && rm /tmp/tomcat.tar.gz \
    # Remove default apps except ROOT (need for 404 redirect)
    && rm -rf /opt/tomcat/webapps/examples \
               /opt/tomcat/webapps/docs \
               /opt/tomcat/webapps/host-manager \
               /opt/tomcat/webapps/manager \
               /opt/tomcat/webapps/ROOT

ENV CATALINA_HOME=/opt/tomcat
ENV CATALINA_BASE=/data/catalina
ENV PATH="$CATALINA_HOME/bin:$PATH"

# Copy the WAR from build stage
COPY --from=builder /build/target/AgriTrace.war /opt/agritrace/AgriTrace.war

# Copy pom.xml and source for bootstrap CLI (mvn exec:java)
COPY --from=builder /build/target /opt/agritrace/target
COPY pom.xml /opt/agritrace/pom.xml
COPY src /opt/agritrace/src

# Copy server.xml template and entrypoint
COPY docker/demo/server.xml.template /opt/agritrace/server.xml.template
COPY docker/demo/docker-entrypoint.sh /opt/agritrace/docker-entrypoint.sh
RUN chmod +x /opt/agritrace/docker-entrypoint.sh

# Runtime data volume (Catalina base, PKI, manifests — node-specific)
VOLUME ["/data"]

# App HTTPS port and P2P mTLS port (set by server.xml at runtime)
EXPOSE 8443 9443

WORKDIR /opt/agritrace

ENTRYPOINT ["/opt/agritrace/docker-entrypoint.sh"]
