FROM debian:bookworm-slim

ARG BRIDGE_VERSION=v0.1.25

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g opencode-ai \
    && which opencode

COPY openmate-linux-x86_64 /usr/local/bin/openmate
RUN chmod +x /usr/local/bin/openmate

COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

RUN mkdir -p /root/workspace \
    && echo "# OpenMate Test Workspace" > /root/workspace/README.md

WORKDIR /root/workspace
EXPOSE 4097

ENTRYPOINT ["/app/entrypoint.sh"]
