"""Configuration and role instructions, with no credentials stored in source files."""
import json
import os
from pathlib import Path
import re

from .client import ROOT
from .context import Dictionary
from .models import ChatModel
from .workflow import Workflow, WorkflowLimits


def load_env(path=None):
    """Load simple KEY=value entries without executing or expanding file content."""
    env_path = Path(path) if path is not None else ROOT / ".env"
    if not env_path.exists():
        if path is None:
            return False
        raise FileNotFoundError(f"Environment file was not found: {env_path}")
    if env_path.stat().st_size > 65536:
        raise ValueError("Environment file exceeds 64 KiB")

    entries = {}
    for number, line in enumerate(env_path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        name, separator, value = line.partition("=")
        name, value = name.strip(), value.strip()
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            raise ValueError(f"Invalid environment entry on line {number}")
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError(f"Invalid quoted environment value on line {number}")
            value = value[1:-1]
        if "\0" in value:
            raise ValueError(f"Invalid environment value on line {number}")
        entries[name] = value

    for name, value in entries.items():
        os.environ.setdefault(name, value)
    return True


def instructions(name):
    path = ROOT / ".agents" / "skills" / name / "SKILL.md"
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        raise ValueError(f"Invalid skill frontmatter: {name}")
    return text.split("---", 2)[2].strip()


def load_config(path=None):
    if path is None:
        return {}
    path = Path(path)
    if path.stat().st_size > 65536:
        raise ValueError("Configuration exceeds 64 KiB")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or set(data) - {"model", "database", "workflow", "dictionary"}:
        raise ValueError("Configuration supports model, database, workflow, and dictionary")
    for name in ["model", "database", "workflow"]:
        if name in data and not isinstance(data[name], dict):
            raise ValueError(f"Configuration {name} must be an object")
    if data.get("dictionary"):
        data["dictionary"] = str(path.resolve().parent / data["dictionary"])
    return data


def make_workflow(client, config, *, model_name=None, base_url=None, dictionary_path=None, baseline=False):
    model_config = dict(config.get("model", {}))
    if model_name:
        model_config["model"] = model_name
    if base_url:
        model_config["base_url"] = base_url
    if not model_config.get("model"):
        raise ValueError("Choose a model through --model, SQL_AGENT_MODEL, or the model configuration")
    dictionary_path = dictionary_path or config.get("dictionary")
    dictionary = Dictionary.load(dictionary_path) if dictionary_path else Dictionary()
    return Workflow(client, ChatModel(**model_config), dictionary,
                    instructions("sql-analysis"), instructions("result-explanation"),
                    limits=WorkflowLimits(**config.get("workflow", {})), baseline=baseline)
