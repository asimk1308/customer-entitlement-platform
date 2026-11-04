#!/bin/sh
# Runs the Postman collection, starting the stack first if it isn't already running.
set -u
STARTED=""
mkdir -p var
if ! curl -sf localhost:8000/healthz >/dev/null 2>&1; then
    "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar target/entitlement-platform-1.0.0.jar > var/postman-stack.log 2>&1 &
    STARTED=$!
    tries=0
    until curl -sf localhost:8000/healthz >/dev/null 2>&1; do
        tries=$((tries + 1))
        [ "$tries" -gt 150 ] && { echo "The stack did not start"; kill "$STARTED"; exit 1; }
        sleep 0.2
    done
fi
npx --yes newman@6.2.1 run postman/collection.json -e postman/environment.json \
    --timeout-request 10000 "$@"
RESULT=$?
if [ -n "$STARTED" ]; then
    kill "$STARTED"
fi
exit $RESULT
