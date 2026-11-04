"""Carries the user's identity from the agent to the API.

The user's token is issued for this server (audience `copilot-mcp`) and is never forwarded: the MCP
spec forbids token passthrough, and the API would reject it anyway. Each tool call exchanges it
(RFC 8693) for a token that lasts about a minute, holds only the scopes that call needs, and names
both the user (`sub`) and the agent (`act.sub`)."""
import httpx
import jwt

from copilot_mcp.config import API_AUDIENCE, MCP_AUDIENCE, Config

TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange"
ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"


class IdentityError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


async def verify_token(http: httpx.AsyncClient, token: str, audience: str, issuer: str) -> dict:
    """Checks the signature, issuer, expiry and audience against the provider's published keys."""
    try:
        keys = (await http.get("/jwks.json")).raise_for_status().json()["keys"]
    except httpx.HTTPError as error:
        raise IdentityError("identity_unavailable", "Can't reach the identity provider.") from error
    try:
        kid = jwt.get_unverified_header(token).get("kid")
        key = next((key for key in keys if key["kid"] == kid), None)
        if key is None:
            raise IdentityError("invalid_user_token", "The user token has an unknown signing key.")
        return jwt.decode(
            token,
            jwt.PyJWK(key).key,
            algorithms=["RS256"],
            audience=audience,
            issuer=issuer,
            options={"require": ["exp", "sub", "aud", "iss"]},
        )
    except jwt.InvalidTokenError as error:
        raise IdentityError("invalid_user_token", "The user token is invalid or expired.") from error


class UserIdentity:
    def __init__(self, config: Config, http: httpx.AsyncClient, claims: dict):
        self._config = config
        self._http = http
        self.subject: str = claims["sub"]
        self.scopes = frozenset(claims.get("scope", "").split())

    @classmethod
    async def load(cls, config: Config, http: httpx.AsyncClient) -> "UserIdentity":
        claims = await verify_token(http, config.user_token, MCP_AUDIENCE, config.issuer)
        return cls(config, http, claims)

    async def token_for(self, *scopes: str) -> str:
        """Scopes the user lacks are dropped by the provider, not added. A call that ends up with no
        scope at all fails."""
        try:
            response = await self._http.post(
                "/token",
                data={
                    "grant_type": TOKEN_EXCHANGE,
                    "client_id": self._config.agent_id,
                    "client_secret": self._config.agent_secret,
                    "subject_token": self._config.user_token,
                    "subject_token_type": ACCESS_TOKEN_TYPE,
                    "audience": API_AUDIENCE,
                    "scope": " ".join(scopes),
                },
            )
        except httpx.HTTPError as error:
            raise IdentityError(
                "identity_unavailable", "Can't reach the identity provider."
            ) from error
        if response.status_code == 200:
            return response.json()["access_token"]
        error_code = response.json().get("error", "token_exchange_failed")
        if error_code == "invalid_scope":
            raise IdentityError("not_permitted", "This user isn't permitted to do this.")
        raise IdentityError(
            "session_invalid", "The user's session is no longer valid. Ask them to sign in again."
        )
