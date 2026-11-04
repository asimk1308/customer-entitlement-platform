"""`PYTHONPATH=mcp python -m copilot_mcp`, speaking MCP over stdio. Stdout carries the protocol, so
anything else written there would corrupt it."""
import anyio

from copilot_mcp.config import Config
from copilot_mcp.server import build_server


async def main() -> None:
    # One event loop throughout, as the HTTP clients built here are tied to the loop that uses them.
    server = await build_server(Config.from_env())
    await server.run_stdio_async()


if __name__ == "__main__":
    anyio.run(main)
