# ============================================================
# AgriTrace Demo Dockerfile — Multi-stage build
# Stage 1: Maven build (produces AgriTrace.war)
# Stage 2: Official Tomcat 10.1 (Temurin JDK 17) runtime image
# No secrets, no private keys, no local paths embedded.
# ============================================================

# ---- Stage 1: Build ----------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

# Install Maven
RUN apt-get update -qq && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*

# Copy pom.xml and source code
COPY pom.xml ./
COPY src ./src

# Build WAR (skip tests — unit tests run separately)
RUN mvn -q -B clean package -DskipTests

# ---- Stage 2: Runtime --------------------------------------
FROM tomcat:10.1-jdk17-temurin-jammy AS runtime

LABEL org.opencontainers.image.title="AgriTrace Demo Node"
LABEL org.opencontainers.image.description="Mini-AgriTrace 3-node demo — single Tomcat node"

# Install tools needed for bootstrap and mTLS
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends \
        curl \
        openssl \
        maven \
    && rm -rf /var/lib/apt/lists/*

# Link standard /opt/tomcat path to Tomcat installation and remove sample apps
RUN ln -s /usr/local/tomcat /opt/tomcat \
    && rm -rf /usr/local/tomcat/webapps/* /usr/local/tomcat/webapps.dist

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

# App HTTPS and P2P mTLS ports
EXPOSE 8443 8444 8445 9443 9444 9445

WORKDIR /opt/agritrace

ENTRYPOINT ["/opt/agritrace/docker-entrypoint.sh"]
