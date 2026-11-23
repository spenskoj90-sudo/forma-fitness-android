"""Run the bounded synthetic clamp task against a local OpenAI-compatible model."""
import argparse
import ast
import difflib
import fcntl
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request

TASK = "clamp-v1"
MODELS = ("qwen3-1.7b", "qwen2.5-coder-3b")
BASELINE = "def bounded(n):\n    return max(0, n)\n"
TOOL = {
    "type": "function",
    "function": {
        "name": "edit_bounds",
        "description": "Replace the single return expression in bounds.py",
        "parameters": {
            "type": "object",
            "properties": {"expression": {"type": "string"}},
            "required": ["expression"],
            "additionalProperties": False,
        },
    },
}


class PilotFailure(Exception):
    pass


def valid_expression(node):
    if isinstance(node, ast.Constant):
        return type(node.value) is int and 0 <= node.value <= 10
    if isinstance(node, ast.Name):
        return node.id == "n"
    if isinstance(node, ast.IfExp):
        condition = node.test
        return (
            isinstance(condition, ast.Compare)
            and len(condition.ops) == 1
            and isinstance(condition.ops[0], (ast.Lt, ast.LtE, ast.Gt, ast.GtE))
            and valid_expression(condition.left)
            and valid_expression(condition.comparators[0])
            and valid_expression(node.body)
            and valid_expression(node.orelse)
        )
    return (
        isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id in {"min", "max"}
        and len(node.args) == 2
        and not node.keywords
        and all(valid_expression(arg) for arg in node.args)
    )


def validated_source(expression):
    if not isinstance(expression, str) or len(expression) > 80:
        raise PilotFailure("INVALID_EDIT")
    try:
        node = ast.parse(expression.strip(), mode="eval")
    except (SyntaxError, ValueError):
        raise PilotFailure("INVALID_EDIT") from None
    if not valid_expression(node.body):
        raise PilotFailure("INVALID_EDIT")
    normalized = ast.unparse(node.body)
    if len(normalized) > 80:
        raise PilotFailure("INVALID_EDIT")
    return f"def bounded(n):\n    return {normalized}\n"


def verify_source(source):
    namespace = {"__builtins__": {"min": min, "max": max}}
    exec(compile(source, "bounds.py", "exec"), namespace)
    for n in range(-128, 129):
        if namespace["bounded"](n) != sorted([0, n, 9])[1]:
            raise PilotFailure("CHECKS_FAILED")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def propose(port, model):
    body = {
        "model": model,
        "messages": [{
            "role": "user",
            "content": (
                f"Current Python bounds.py:\n{BASELINE}\n"
                "Fix bounded(n) to clamp any integer to the inclusive range 0 to 9. "
                "Call edit_bounds with the Python return expression using n, min and max."
            ),
        }],
        "tools": [TOOL],
        "tool_choice": "required",
        "temperature": 0,
        "max_tokens": 128,
    }
    if model == MODELS[1]:
        del body["tools"], body["tool_choice"]
        body["messages"][0]["content"] = (
            "Fix this Python function: def bounded(n): return max(0, n). "
            "Preserve the lower bound and add an upper bound of 9. Output a JSON object "
            "whose expression value is only the Python return expression. Do not include the keyword return."
        )
        body["response_format"] = {"type": "json_schema", "json_schema": {
            "name": "edit_bounds", "strict": True, "schema": TOOL["function"]["parameters"],
        }}
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=40) as response:
            data = response.read(16385)
    except urllib.error.HTTPError as error:
        raise PilotFailure("PROVIDER_QUOTA" if error.code == 429 else "PROVIDER_HTTP") from None
    except (OSError, http.client.HTTPException):
        raise PilotFailure("PROVIDER_UNREACHABLE") from None
    if len(data) > 16384:
        raise PilotFailure("INVALID_RESPONSE")
    try:
        result = json.loads(data)
        message = result["choices"][0]["message"]
        if model == MODELS[1]:
            args = json.loads(message["content"])
        else:
            calls = message["tool_calls"]
            function = calls[0]["function"]
            if len(calls) != 1 or function["name"] != "edit_bounds":
                raise ValueError
            args = json.loads(function["arguments"])
        if not isinstance(args, dict) or set(args) != {"expression"}:
            raise ValueError
        return args["expression"]
    except (ValueError, KeyError, IndexError, TypeError):
        raise PilotFailure("INVALID_RESPONSE") from None


def write_atomic(path, content):
    temp = path.with_suffix(".tmp")
    with temp.open("w", encoding="utf-8") as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    temp.replace(path)
    directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def run(output, revision, port, model):
    checkpoint = output / "checkpoint.json"
    identity = {"task": TASK, "base_sha": revision, "baseline_sha256": hashlib.sha256(BASELINE.encode()).hexdigest()}
    report = {**identity, "model": model, "agent": "bounded-tool-runner", "status": "NOT_RUN", "checks": "NOT_RUN"}

    def save():
        write_atomic(checkpoint, json.dumps(report, indent=2) + "\n")

    if checkpoint.exists():
        try:
            previous = json.loads(checkpoint.read_text(encoding="utf-8"))
            if any(previous[key] != value for key, value in identity.items()):
                raise ValueError
            if "expression" in previous:
                validated_source(previous["expression"])
                if previous["model"] not in MODELS or previous["agent"] != report["agent"]:
                    raise ValueError
                report.update(expression=previous["expression"], agent=previous["agent"], model=previous["model"])
        except (ValueError, KeyError, TypeError, PilotFailure):
            print("CHECKPOINT_MISMATCH", file=sys.stderr)
            return 1

    try:
        if "expression" not in report:
            report["next"] = "request_model_edit"
            save()
            expression = propose(port, model)
            report["expression"] = validated_source(expression).split("    return ", 1)[1].strip()
        report["next"] = "verify_candidate"
        save()
        source = validated_source(report["expression"])
        verify_source(source)
        patch = "".join(difflib.unified_diff(
            BASELINE.splitlines(keepends=True), source.splitlines(keepends=True),
            fromfile="a/bounds.py", tofile="b/bounds.py",
        ))
        write_atomic(output / "bounds.py", source)
        write_atomic(output / "candidate.patch", patch)
        report.update(status="PASS", checks="PASS", next="review_synthetic_patch",
                      patch_sha256=hashlib.sha256(patch.encode()).hexdigest())
        save()
        return 0
    except PilotFailure as error:
        report.update(status="FAIL", error=str(error))
        if str(error) == "CHECKS_FAILED":
            report.update(checks="FAIL", next="request_model_edit")
            del report["expression"]
        save()
        print(str(error), file=sys.stderr)
        return 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--port", type=int, default=18787)
    parser.add_argument("--model", choices=MODELS, default=MODELS[0])
    args = parser.parse_args()
    if not re.fullmatch("[a-f0-9]{40}", args.revision) or not 1 <= args.port <= 65535:
        parser.error("expected an exact Git SHA and a valid TCP port")
    args.output.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (args.output / ".writer").open("a", encoding="utf-8") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("WRITER_BUSY", file=sys.stderr)
            return 1
        return run(args.output, args.revision, args.port, args.model)


if __name__ == "__main__":
    sys.exit(main())
