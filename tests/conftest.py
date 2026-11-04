"""The whole Java stack as a real process on separate ports, so a running `make run` isn't disturbed.
Python is only here because the MCP server and Schemathesis are Python: every test crosses real HTTP
into the jar, and nothing is mocked on this side of it."""
import glob
import os
import shutil
import signal
import subprocess
import time
from pathlib import Path

import httpx
import pytest

ROOT = Path(__file__).parents[1]
API, UPSTREAM, RECEIVER, IDP = 18000, 19001, 19002, 19100
BREW_JDK = "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"


def java_home() -> str | None:
    if os.environ.get("JAVA_HOME"):
        return os.environ["JAVA_HOME"]
    return BREW_JDK if Path(BREW_JDK).exists() else None


def java_command() -> str:
    home = java_home()
    return f"{home}/bin/java" if home else shutil.which("java") or "java"


def built_jar() -> str:
    jars = glob.glob(str(ROOT / "target/entitlement-platform-*.jar"))
    if not jars:
        env = os.environ | ({"JAVA_HOME": java_home()} if java_home() else {})
        subprocess.run([str(ROOT / "mvnw"), "-q", "-DskipTests", "package"], cwd=ROOT, env=env, check=True)
        jars = glob.glob(str(ROOT / "target/entitlement-platform-*.jar"))
    return jars[0]


@pytest.fixture(scope="session")
def stack(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("stack")
    log_path = tmp / "stack.log"
    env = os.environ | {
        "API_PORT": str(API),
        "UPSTREAM_PORT": str(UPSTREAM),
        "RECEIVER_PORT": str(RECEIVER),
        "IDP_PORT": str(IDP),
        "UPSTREAM_URL": f"http://localhost:{UPSTREAM}",
        "IDP_URL": f"http://localhost:{IDP}",
        "IDP_ISSUER": f"http://localhost:{IDP}",
        "WEBHOOK_ALLOWED_HOSTS": f"localhost:{RECEIVER}",
        "PLATFORM_DB_PATH": str(tmp / "platform.db"),
        "WAREHOUSE_PATH": str(tmp / "warehouse.db"),
        # High enough that fuzzing tests the API rather than the rate limiter.
        "RATE_LIMIT_PER_MINUTE": "100000",
        # The stack outlives each test, so a test injecting faults mustn't leave a circuit open.
        "PLATFORM_CASES_FAILURETHRESHOLD": "100000",
        "PLATFORM_USAGE_FAILURETHRESHOLD": "100000",
        "PLATFORM_ENTITLEMENTS_FAILURETHRESHOLD": "100000",
    }
    with log_path.open("w") as log:
        process = subprocess.Popen(
            [java_command(), "-jar", built_jar()],
            cwd=tmp,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
    try:
        deadline = time.monotonic() + 60
        while True:
            try:
                httpx.get(f"http://localhost:{API}/healthz").raise_for_status()
                httpx.get(f"http://localhost:{IDP}/jwks.json").raise_for_status()
                httpx.get(f"http://localhost:{UPSTREAM}/cases").raise_for_status()
                break
            except httpx.HTTPError:
                assert time.monotonic() < deadline, log_path.read_text()[-2000:]
                time.sleep(0.2)
        yield {
            "log": log_path,
            "root": ROOT,
            "db": tmp / "platform.db",
            "api_url": f"http://localhost:{API}",
            "idp_url": f"http://localhost:{IDP}",
            "upstream_url": f"http://localhost:{UPSTREAM}",
        }
    finally:
        process.send_signal(signal.SIGINT)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
