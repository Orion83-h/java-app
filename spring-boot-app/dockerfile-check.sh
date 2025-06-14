#!/bin/bash

DOCKERFILE="spring-boot-app/Dockerfile.dev"
    if [ ! -f "$DOCKERFILE" ]; then
        echo "::error ::Dockerfile not found at path $DOCKERFILE"
            exit 1
    fi

    if grep -q '^FROM scratch' "$DOCKERFILE"; then
        echo "::error ::Avoid using FROM scratch unless absolutely necessary"
        exit 1
    fi

    if ! grep -q '^USER ' "$DOCKERFILE"; then
        echo "::error ::Dockerfile does not define a USER — container will run as root"
        exit 1
    fi

        USER_LINE=$(grep '^USER ' "$DOCKERFILE" | tail -n1)
        USER_VALUE=$(echo "$USER_LINE" | awk '{print $2}')
    if [ "$USER_VALUE" = "root" ]; then
       echo "::error ::Dockerfile sets USER to root — define a non-root user"
        exit 1
    fi

    echo " Dockerfile defines a secure non-root user: $USER_VALUE"