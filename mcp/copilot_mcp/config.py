"""Settings for one server process. Stdio has no per-request credentials, so, as the MCP spec says
for this transport, the acting user's token arrives through the environment."""
import os
from dataclasses import dataclass
from pathlib import Path

MCP_AUDIENCE = "copilot-mcp"
APPROVAL_AUDIENCE = "copilot-approval"
API_AUDIENCE = "platform-api"


@dataclass(frozen=True)
class Config:
    user_token: str
    api_url: str = "http://localhost:8000"
    idp_url: str = "http://localhost:9100"
    issuer: str = "http://localhost:9100"
    agent_id: str = "support-copilot"
    # Dev-only default, matching the mock provider. Real deployments use workload identity.
    agent_secret: str = "dev-only-agent-secret"
    db_path: Path = Path("var/mcp.db")

    @classmethod
    def from_env(cls) -> "Config":
        return cls(
            user_token=os.environ["COPILOT_USER_TOKEN"],
            api_url=os.getenv("PLATFORM_API_URL", cls.api_url),
            idp_url=os.getenv("IDP_URL", cls.idp_url),
            issuer=os.getenv("IDP_ISSUER", cls.issuer),
            agent_secret=os.getenv("COPILOT_AGENT_SECRET", cls.agent_secret),
            db_path=Path(os.getenv("MCP_DB_PATH", cls.db_path)),
        )
