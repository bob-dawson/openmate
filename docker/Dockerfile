FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates sqlite3 \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g @opencode/cli@latest \
    && which opencode2

COPY openmate-linux-x86_64 /usr/local/bin/openmate
RUN chmod +x /usr/local/bin/openmate

COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

RUN mkdir -p /root/workspace /root/.config/opencode \
    && echo "# OpenMate V2 Test Workspace" > /root/workspace/README.md

COPY v2-opencode-config.json /root/.config/opencode/opencode.json

WORKDIR /root/workspace
EXPOSE 4097

ENTRYPOINT ["/app/entrypoint.sh"]
