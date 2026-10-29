"""Small structured-output provider boundary; no model SDK is required."""
from dataclasses import dataclass, field
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

from .client import json_text


class ModelError(RuntimeError):
    pass


@dataclass
class ModelReply:
    data: dict
    usage: dict = field(default_factory=dict)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        raise ModelError("Model endpoint redirected the request; configure its final URL explicitly")


class ChatModel:
    """Hosted/local Chat Completions servers must support the configured JSON mode."""
    def __init__(self, model, *, base_url="https://api.openai.com/v1", api_key_env=None,
                 answer_model=None, response_format="json_schema", token_limit_field="max_completion_tokens",
                 max_output_tokens=900, timeout=45, reasoning_effort=None):
        address = urllib.parse.urlsplit(base_url)
        local = address.hostname in {"localhost", "127.0.0.1", "::1"}
        if not address.hostname or address.scheme not in {"http", "https"} or address.username or address.password or address.query or address.fragment:
            raise ValueError("Supply an HTTP(S) API base URL without credentials, query, or fragment")
        if address.scheme != "https" and not local:
            raise ValueError("Use HTTPS for a remote model endpoint")
        if not model or response_format not in {"json_schema", "json_object"}:
            raise ValueError("Model name and a supported JSON response format are required")
        if token_limit_field not in {"max_completion_tokens", "max_tokens"} or max_output_tokens < 1 or timeout <= 0:
            raise ValueError("Invalid model limits")
        self.model, self.answer_model = model, answer_model or model
        self.base_url, self.key_env = base_url.rstrip("/"), api_key_env
        self.format, self.token_field = response_format, token_limit_field
        self.max_output_tokens, self.timeout = max_output_tokens, timeout
        self.hosted = address.hostname == "api.openai.com"
        if self.key_env is None and self.hosted:
            self.key_env = "OPENAI_API_KEY"
        if reasoning_effort not in {None, "none", "low", "medium", "high"}:
            raise ValueError("Unsupported reasoning effort setting")
        self.reasoning_effort = reasoning_effort
        self._opener = urllib.request.build_opener(NoRedirect())

    def complete(self, role, instructions, context, schema):
        key = os.environ.get(self.key_env, "") if self.key_env else ""
        if self.hosted and not key:
            raise ModelError(f"Set {self.key_env} before using the hosted API")
        output_format = {"type": self.format}
        if self.format == "json_schema":
            output_format["json_schema"] = {"name": role, "strict": True, "schema": schema}
        body = {
            "model": self.answer_model if role == "answer" else self.model,
            "messages": [{"role": "system", "content": instructions},
                         {"role": "user", "content": json_text(context)}],
            "response_format": output_format, self.token_field: self.max_output_tokens, "store": False,
        }
        if self.reasoning_effort is not None:
            body["reasoning_effort"] = self.reasoning_effort
        encoded = json_text(body).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if key:
            headers["Authorization"] = "Bearer " + key
        request = urllib.request.Request(self.base_url + "/chat/completions", data=encoded, headers=headers)
        started = time.perf_counter()
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise ModelError("Model response exceeded its byte limit")
            value = json.loads(raw)
            choice = value["choices"][0]
            if choice.get("finish_reason") != "stop" or choice["message"].get("refusal"):
                raise ModelError("Model refused or did not finish its structured response")
            data = json.loads(choice["message"]["content"])
            if not isinstance(data, dict):
                raise ModelError("Model output must be a JSON object")
        except urllib.error.HTTPError as error:
            raise ModelError(f"Model API returned HTTP {error.code}; request was not retried") from error
        except (OSError, ValueError, KeyError, IndexError, TypeError) as error:
            raise ModelError("Model connection failed or returned invalid JSON; request was not retried") from error
        usage = value.get("usage")
        if not isinstance(usage, dict):
            usage = {}
        def tokens(name):
            count = usage.get(name)
            return count if type(count) is int and count >= 0 else None
        return ModelReply(data, {"model": body["model"], "input_tokens": tokens("prompt_tokens"),
                                 "output_tokens": tokens("completion_tokens"), "request_bytes": len(encoded),
                                 "latency_ms": round((time.perf_counter() - started) * 1000, 2)})
