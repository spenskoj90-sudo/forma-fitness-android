"""Reviewed task definitions. Model output cannot select files or commands."""

import ast
import difflib
import os
from pathlib import Path
import re
import io
import signal
import subprocess
import tempfile
import tarfile

from run import valid_expression

BACKUP = "domain/src/main/kotlin/ru/forma/domain/Backup.kt"
ORIGINAL = "w.status!=Phase.DONE || w.day.cardio!=null || w.sets.size==w.day.exercises.sumOf{it.sets}"
KOTLIN_WORDS = set(
    "prescriptions sets sumOf it groupBy groupingBy mapValues eachCount exercise id "
    "value values key prescription xs pair entry count size list group grouped".split()
)
KOTLIN_TOKEN = re.compile(r"[a-zA-Z][a-zA-Z0-9]*|[0-9]{1,3}|==|!=|&&|\|\||->|[.(){}:,<>+!_]")


def patch(before, after, path):
    return "".join(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                       fromfile=f"a/{path}", tofile=f"b/{path}"))


def kotlin_expression(candidate, failure):
    tokens = KOTLIN_TOKEN.findall(candidate)
    if "".join(tokens) != re.sub(r"[ \t]", "", candidate):
        raise failure("policy", "Use one line and only the listed Kotlin collection expressions.")
    unknown = sorted({token for token in tokens if token[0].isalpha() and token not in KOTLIN_WORDS})
    if unknown:
        raise failure("policy", f"Identifiers outside the listed Kotlin vocabulary: {', '.join(unknown)}.")
    stack = []
    for token in tokens:
        if token in {"(", "{"}:
            stack.append(token)
        elif token in {")", "}"}:
            if not stack or stack.pop() != {")": "(", "}": "{"}[token]:
                raise failure("policy", "Balance parentheses and braces.")
    if not tokens or stack:
        raise failure("policy", "Provide a complete expression.")
    return candidate.strip()


def backup_task(repo, revision, failure):
    try:
        before = subprocess.check_output(["git", "show", f"{revision}:{BACKUP}"], cwd=repo, text=True)
    except subprocess.CalledProcessError:
        raise failure("base", "Backup source is unavailable at the exact revision.") from None
    if before.count(ORIGINAL) != 1:
        raise failure("base", "The original PR #15 defect is no longer present on this revision.")

    original_line = next(line for line in before.splitlines() if ORIGINAL in line)
    prefix = f"   require({ORIGINAL})"
    if not original_line.startswith(prefix + "{"):
        raise failure("base", "Unexpected original require statement.")
    message = original_line.removeprefix(prefix)

    def verify(candidate):
        expression = kotlin_expression(candidate, failure)
        replacement = (
            "if (w.status == Phase.DONE && w.day.cardio == null) {\n"
            "    val prescriptions = w.day.exercises\n"
            f"    val plannedSets: Map<String, Int> = ({expression})\n"
            "    val loggedSets = w.sets.groupingBy { it.exerciseId }.eachCount()\n"
            f"    require(loggedSets == plannedSets){message}\n"
            "   }"
        )
        after = before.replace(original_line.lstrip(), replacement)
        with tempfile.TemporaryDirectory(prefix="forma-backup-replay-") as tmp:
            root = Path(tmp)
            archive = subprocess.check_output(
                ["git", "archive", revision, "domain/src", "scripts/ai-pilot/BackupReplayTest.kt"],
                cwd=repo,
            )
            with tarfile.open(fileobj=io.BytesIO(archive)) as snapshot:
                snapshot.extractall(root, filter="data")
            (root / BACKUP).write_text(after)
            (root / "scripts/ai-pilot/BackupReplayTest.kt").rename(
                root / "domain/src/test/kotlin/BackupReplayTest.kt"
            )
            with subprocess.Popen(
                ["bash", str(repo / "scripts/ai-pilot/verify-domain.sh"), str(root)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                start_new_session=True,
                env={key: os.environ[key] for key in ("PATH", "JAVA_HOME", "PILOT_KOTLIN_CACHE")
                     if key in os.environ},
            ) as verifier:
                try:
                    output, _ = verifier.communicate(timeout=100)
                except subprocess.TimeoutExpired:
                    os.killpg(verifier.pid, signal.SIGKILL)
                    verifier.communicate()
                    raise failure("compiler", "Kotlin verification timed out.") from None
            if verifier.returncode:
                output = output.replace(str(root), ".")
                lines = [line for line in output.splitlines() if "error:" in line]
                if lines:
                    raise failure("compile", "\n".join(lines)[:1400])
                if "FAILURES!!!" in output:
                    raise failure("tests", "A domain test failed. Equal total set counts are not "
                                  "enough. Compare logged counts and summed prescribed counts by exercise ID.")
                raise failure("compiler", "Kotlin verifier failed to start.")
        return patch(before, after, BACKUP)

    brief = (
        "Implement the Kotlin initializer of val plannedSets: Map<String, Int>. "
        "The only input variable is prescriptions: List<Prescription>. Each Prescription has exercise.id (String) "
        "and sets (Int). Return a map from exercise ID to the sum of sets over all matching "
        "prescriptions. Two entries with the same ID and sets 2 and 3 must yield 5, not 2 or 3. "
        "Use prescriptions.groupBy to get lists by exercise ID, then transform the map values. "
        "Kotlin mapValues passes a Map.Entry; its value property is the list for that ID. "
        "Return only one Kotlin expression, with no variable declarations. "
        f"Allowed identifiers: {', '.join(sorted(KOTLIN_WORDS))}."
    )
    return verify, brief


def synthetic_task(low, high, path, failure):
    before = f"def bounded(n):\n    return max({low}, n)\n"

    def verify(candidate):
        try:
            node = ast.parse(candidate.strip(), mode="eval")
        except SyntaxError as error:
            raise failure("compile", error.msg) from None
        if not valid_expression(node.body):
            raise failure("policy", "Use only n, min, max and constants from 0 through 10.")
        after = f"def bounded(n):\n    return {ast.unparse(node)}\n"
        namespace = {"__builtins__": {"min": min, "max": max}}
        exec(compile(after, path, "exec"), namespace)
        if any(namespace["bounded"](n) != min(max(low, n), high) for n in range(-128, 129)):
            raise failure("tests", f"Clamp the value to the inclusive range {low} to {high}.")
        return patch(before, after, path)

    return verify, (f"Fix {path}: {before} Clamp n to {low} through {high}, inclusive. "
                    "Return only the expression using n, min, max and integer constants.")


def make_tasks(repo, revision, package, task, failure):
    verify, brief = synthetic_task(0, 9, "bounds.py", failure)
    follow, follow_brief = synthetic_task(2, 7, "followup_bounds.py", failure)
    tasks = []
    if package == "backup-replay":
        try:
            if not os.environ.get("PILOT_KOTLIN_CACHE"):
                raise failure("compiler", "Kotlin dependencies unavailable.")
            backup, backup_brief = backup_task(repo, revision, failure)
            tasks.append(task("backup-pr15", (), backup, backup_brief))
        except failure as error:
            tasks.append(task("backup-pr15", (), None, blocker=str(error)))
    return tasks + [task("clamp", (), verify, brief),
                    task("clamp-followup", ("clamp",), follow, follow_brief)]
