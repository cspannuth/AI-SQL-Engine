"""Terminal entry point and executable JSON Lines database tools."""
import argparse
import json
import os
from pathlib import Path
import sys

from .client import DatabaseClient, DatabaseError, json_text
from .settings import load_config, load_env, make_workflow


def display_text(value):
    # Escape embedded terminal controls from names, rows, and model output.
    return json.dumps(str(value), ensure_ascii=False)[1:-1]


def show(output):
    print(display_text(output["answer"]))
    result = output.get("result")
    if result:
        print("SQL: " + display_text(result["sql"]))
        print("Parameters: " + json_text(result["parameters"]))
        print("Columns: " + json_text(result["columns"]))
        for row in result["rows"][:10]:
            print(json_text(row))
        if len(result["rows"]) > 10:
            print("Display shows the first 10 rows; the result artifact retains the returned rows.")
        print(f"Result: {result['resultId']} | complete={result['complete']} | rows={result['returnedRows']}")
    if output.get("assumptions"):
        print("Assumptions: " + json_text(output["assumptions"]))
    if output.get("metrics"):
        print("Metric context: " + json_text(output["metrics"]))
    if output.get("answer_note"):
        print(display_text(output["answer_note"]))
    print("Usage: " + json_text(output["usage"]))


def tool_session(client):
    """Explicit, deterministic requests; schema-only mode is enforced in Java too."""
    methods = {
        "list_tables": (set(), client.list_tables),
        "get_capabilities": (set(), client.get_capabilities),
        "describe_table": ({"table"}, client.describe_table),
        "query_readonly": ({"sql", "parameters", "limits"}, client.query_readonly),
        "get_result": ({"result_id"}, client.get_result),
    }
    for line in iter(lambda: sys.stdin.readline(256 * 1024 + 1), ""):
        request_id = None
        try:
            if len(line) == 256 * 1024 + 1 and not line.endswith("\n"):
                while line and not line.endswith("\n"):
                    line = sys.stdin.readline(256 * 1024 + 1)
                raise ValueError("Tool request exceeds 256 KiB")
            if len(line.encode("utf-8")) > 256 * 1024:
                raise ValueError("Tool request exceeds 256 KiB")
            request = json.loads(line)
            request_id = request.get("id")
            if request_id is not None and type(request_id) not in {str, int}:
                raise ValueError("Tool request ID must be a string or integer")
            operation = request["operation"]
            arguments = request.get("arguments", {})
            if operation not in methods or set(request) - {"id", "operation", "arguments"}:
                raise ValueError("Unknown tool operation or request field")
            fields, method = methods[operation]
            if not isinstance(arguments, dict) or set(arguments) - fields:
                raise ValueError("Unknown tool arguments")
            data = method(**arguments)
            response = {"id": request_id, "ok": True, "data": data}
        except (ValueError, KeyError, TypeError, AttributeError, DatabaseError) as error:
            response = {"id": request_id, "ok": False,
                        "error": {"code": getattr(error, "code", "INVALID_REQUEST"), "message": str(error)}}
        print(json_text(response), flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ["ask", "tools"]:
        command = commands.add_parser(name)
        command.add_argument("--db", required=True, help="Existing database directory")
        command.add_argument("--config", help="JSON configuration; secrets come from the environment or .env")
        command.add_argument("--allow-table", action="append", help="Fixed table policy; repeat to allow several tables")
        if name == "ask":
            command.add_argument("--env-file", help="Environment file; defaults to the repository .env")
            command.add_argument("question", nargs="?", help="Omit to open a persistent conversation")
            command.add_argument("--model")
            command.add_argument("--base-url")
            command.add_argument("--dictionary")
            command.add_argument("--answer", choices=["auto", "direct", "model"], default="auto")
            command.add_argument("--chart", action="store_true", help="Include the visualization handoff; does not render a chart")
            command.add_argument("--json", action="store_true")
            command.add_argument("--export", help="Save a one-shot answer, full result, and context to a new JSON file")
        else:
            command.add_argument("--schema-only", action="store_true")
    options = parser.parse_args(argv)
    try:
        if options.command == "ask":
            load_env(options.env_file)
        config = load_config(options.config)
        database = dict(config.get("database", {}))
        if options.allow_table:
            database["allowed_tables"] = options.allow_table
        if options.command == "tools":
            database["schema_only"] = options.schema_only or database.get("schema_only", False)
        elif options.export and not options.question:
            raise ValueError("--export requires a one-shot question")
        with DatabaseClient(options.db, **database) as client:
            if options.command == "tools":
                tool_session(client)
                return 0
            workflow = make_workflow(client, config, model_name=options.model or os.environ.get("SQL_AGENT_MODEL"),
                                     base_url=options.base_url or os.environ.get("SQL_AGENT_BASE_URL"),
                                     dictionary_path=options.dictionary)
            if options.question:
                output = workflow.ask(options.question, explain=options.answer, chart=options.chart)
                if options.export:
                    with Path(options.export).open("x", encoding="utf-8") as artifact:
                        artifact.write(json_text(output) + "\n")
                print(json_text(output)) if options.json else show(output)
                return int(output["status"] == "error")
            while True:
                try:
                    question = input("question> ").strip()
                except EOFError:
                    break
                if question in {".quit", "q"}:
                    break
                if question == ".clear":
                    workflow.previous = workflow.previous_result = workflow.pending_question = None
                    continue
                if question in {".tables", ".capabilities"} or question.startswith((".schema ", ".result ")):
                    try:
                        if question == ".tables": data = client.list_tables()
                        elif question == ".capabilities": data = client.get_capabilities()
                        elif question.startswith(".schema "): data = client.describe_table(question[8:].strip())
                        else: data = client.get_result(question[8:].strip())
                        print(json_text(data))
                    except DatabaseError as error:
                        print(display_text(str(error)))
                    continue
                if question:
                    output = workflow.ask(question, explain=options.answer, chart=options.chart)
                    print(json_text(output)) if options.json else show(output)
            return 0
    except (OSError, ValueError, TypeError, DatabaseError) as error:
        print(display_text(str(error)), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
