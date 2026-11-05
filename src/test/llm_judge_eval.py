#!/usr/bin/env python3
"""LLM-as-judge evaluation runner for RideAgentHub.

This script calls chat SSE APIs, validates rule-based assertions, supports
"multiple acceptable trajectories" via checks.any_of, and writes a text report
with scores.

Dependencies: requests (pip install requests)
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, Iterable, List, Optional, Tuple

import requests


# ----------- SUT (system under test) configuration -----------
SUT_BASE_URL = os.getenv("SUT_BASE_URL", "http://localhost:8080")
SUT_CHAT_PATH = os.getenv("SUT_CHAT_PATH", "/chat/v2/c/{id}")
SUT_RESUME_PATH = os.getenv("SUT_RESUME_PATH", "/chat/v2/r/{id}")
SUT_TOKEN = os.getenv("SUT_TOKEN", "YOUR_TOKEN")
SUT_TOKEN_HEADER = os.getenv("SUT_TOKEN_HEADER", "Authorization")
SUT_MODEL = os.getenv("SUT_MODEL", "deepseek-v3.2")

# ----------- Judge (OpenAI compatible API) configuration -----------
JUDGE_BASE_URL = os.getenv("JUDGE_BASE_URL", "https://api.openai.com/v1")
JUDGE_API_KEY = os.getenv("JUDGE_API_KEY", "YOUR_API_KEY")
JUDGE_MODEL = os.getenv("JUDGE_MODEL", "gpt-4o-mini")
JUDGE_TIMEOUT_SEC = int(os.getenv("JUDGE_TIMEOUT_SEC", "60"))
JUDGE_RESPONSE_FORMAT_JSON = os.getenv("JUDGE_RESPONSE_FORMAT_JSON", "true").lower() in (
    "1",
    "true",
    "yes",
)

CLASSIFICATION_LABELS = {"ORDER", "DAILY", "SUPPORT", "OTHER", "DANGER"}
SIDE_EFFECT_LEVELS = {"low": 1, "medium": 2, "high": 3}


@dataclass
class TurnResult:
    turn_type: str
    user: str
    events: List[Dict[str, Any]] = field(default_factory=list)
    summary: Dict[str, Any] = field(default_factory=dict)
    http_error: Optional[str] = None


@dataclass
class CheckEval:
    failures: List[str]
    passed_points: int
    total_points: int


@dataclass
class CaseResult:
    case_id: str
    description: str
    status: str
    reasons: List[str]
    turns: List[TurnResult]
    judge: Optional[Dict[str, Any]] = None
    rule_score: float = 0.0
    final_score: float = 0.0
    judge_score: Optional[float] = None
    assertion_passed: int = 0
    assertion_total: int = 0


def load_cases(path: str) -> List[Dict[str, Any]]:
    cases: List[Dict[str, Any]] = []
    with open(path, "r", encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            cases.append(json.loads(line))
    return cases


def build_sut_headers() -> Dict[str, str]:
    headers = {
        "Accept": "text/event-stream",
        "Content-Type": "application/json",
    }
    token = SUT_TOKEN
    if token:
        if SUT_TOKEN_HEADER.lower() == "authorization":
            headers["Authorization"] = f"Bearer {token}"
        else:
            headers[SUT_TOKEN_HEADER] = token
    return headers


def build_judge_headers() -> Dict[str, str]:
    return {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {JUDGE_API_KEY}",
    }


def as_list(value: Any) -> List[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def normalize_text(value: Any) -> str:
    text = "" if value is None else str(value)
    lowered = text.lower()
    for ch in ("(", ")", "[", "]", "{", "}", "，", "。", ":", "："):
        lowered = lowered.replace(ch, "")
    return lowered.strip()


def contains_normalized(haystack_list: List[str], needle: str) -> bool:
    n = normalize_text(needle)
    if not n:
        return False
    return any(n in item for item in haystack_list)


def sse_events(resp: requests.Response) -> List[Dict[str, Any]]:
    events: List[Dict[str, Any]] = []
    for raw in resp.iter_lines(decode_unicode=True):
        if not raw:
            continue
        line = raw.strip()
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if not data or data == "[DONE]":
            continue
        try:
            event = json.loads(data)
        except json.JSONDecodeError:
            event = {"type": "_raw", "payload": data}
        events.append(event)
    return events


def summarize_events(events: List[Dict[str, Any]]) -> Dict[str, Any]:
    summary: Dict[str, Any] = {
        "messages": [],
        "tool_starts": [],
        "notifies": [],
        "errors": [],
        "confirms": [],
    }
    for event in events:
        if not isinstance(event, dict):
            continue
        etype = event.get("type")
        payload = event.get("payload")
        if etype == "message":
            if isinstance(payload, str):
                summary["messages"].append(payload)
        elif etype == "tool_start":
            summary["tool_starts"].append(payload)
        elif etype == "notify":
            summary["notifies"].append(payload)
        elif etype == "error":
            summary["errors"].append(payload)
        elif etype == "confirm":
            summary["confirms"].append(payload)
    return summary


def extract_classification(notifies: Iterable[Any]) -> Optional[str]:
    for item in notifies:
        if isinstance(item, str) and item in CLASSIFICATION_LABELS:
            return item
    return None


def collect_tool_starts_before_confirm(events: List[Dict[str, Any]]) -> List[str]:
    result: List[str] = []
    for event in events:
        if not isinstance(event, dict):
            continue
        etype = event.get("type")
        if etype == "confirm":
            break
        if etype == "tool_start":
            result.append(str(event.get("payload")))
    return result


def call_chat(
    session: requests.Session,
    url: str,
    prompt: str,
    model: str,
    headers: Dict[str, str],
    timeout_sec: int,
) -> Tuple[Optional[List[Dict[str, Any]]], Optional[str]]:
    payload: Dict[str, Any] = {"prompt": prompt}
    if model:
        payload["model"] = model
    try:
        resp = session.post(url, json=payload, headers=headers, stream=True, timeout=timeout_sec)
    except requests.RequestException as exc:
        return None, f"request_error: {exc}"
    if resp.status_code != 200:
        try:
            text = resp.text
        except Exception:
            text = ""
        return None, f"http_{resp.status_code}: {text}"
    events = sse_events(resp)
    return events, None


def run_judge(case: Dict[str, Any], turn_results: List[TurnResult]) -> Dict[str, Any]:
    if not JUDGE_API_KEY or JUDGE_API_KEY == "YOUR_API_KEY":
        return {"skipped": True, "reason": "JUDGE_API_KEY not set"}

    rubric = case.get("judge", {}).get("rubric", "")
    must_include = case.get("judge", {}).get("must_include", [])
    must_avoid = case.get("judge", {}).get("must_avoid", [])

    lines: List[str] = []
    for idx, turn in enumerate(turn_results, start=1):
        summary = turn.summary or {}
        messages = "\n".join(summary.get("messages", []))
        tool_starts = summary.get("tool_starts", [])
        notifies = summary.get("notifies", [])
        confirms = summary.get("confirms", [])
        errors = summary.get("errors", [])

        lines.append(f"Turn {idx} ({turn.turn_type}) user: {turn.user}")
        if messages:
            lines.append(f"Turn {idx} assistant messages:\n{messages}")
        if tool_starts:
            lines.append(f"Turn {idx} tool_start: {tool_starts}")
        if notifies:
            lines.append(f"Turn {idx} notify: {notifies}")
        if confirms:
            lines.append(f"Turn {idx} confirm: {confirms}")
        if errors:
            lines.append(f"Turn {idx} error: {errors}")

    convo = "\n".join(lines)
    system_prompt = (
        "You are a strict evaluator for a taxi assistant. "
        "Return JSON only with fields: score (0-5), verdict (pass|fail), reasons (list of short strings)."
    )
    user_prompt = (
        "Evaluate the assistant response for the case below.\n\n"
        f"Case ID: {case.get('id')}\n"
        f"Description: {case.get('description', '')}\n"
        f"Rubric: {rubric}\n"
        f"Must include: {must_include}\n"
        f"Must avoid: {must_avoid}\n\n"
        "Conversation and events:\n"
        f"{convo}"
    )

    payload: Dict[str, Any] = {
        "model": JUDGE_MODEL,
        "temperature": 0,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    }
    if JUDGE_RESPONSE_FORMAT_JSON:
        payload["response_format"] = {"type": "json_object"}

    url = JUDGE_BASE_URL.rstrip("/") + "/chat/completions"
    try:
        resp = requests.post(url, json=payload, headers=build_judge_headers(), timeout=JUDGE_TIMEOUT_SEC)
    except requests.RequestException as exc:
        return {"error": f"judge_request_error: {exc}"}
    if resp.status_code != 200:
        return {"error": f"judge_http_{resp.status_code}: {resp.text}"}

    try:
        data = resp.json()
        content = data["choices"][0]["message"]["content"]
        return json.loads(content)
    except Exception as exc:
        return {"error": f"judge_parse_error: {exc}", "raw": resp.text}


def evaluate_checks_block(checks: Dict[str, Any], ctx: Dict[str, Any], prefix: str = "") -> CheckEval:
    failures: List[str] = []
    passed_points = 0
    total_points = 0

    def assert_one(condition: bool, msg: str) -> None:
        nonlocal passed_points, total_points
        total_points += 1
        if condition:
            passed_points += 1
        else:
            failures.append(prefix + msg)

    first_classification = ctx.get("first_classification")
    classifications_by_turn = ctx.get("classifications_by_turn", [])
    tool_starts_normalized = ctx.get("tool_starts_normalized", [])
    tool_starts_before_confirm_normalized = ctx.get("tool_starts_before_confirm_normalized", [])
    messages = ctx.get("messages", [])
    messages_joined_lower = ctx.get("messages_joined_lower", "")
    confirms = ctx.get("confirms", [])
    errors = ctx.get("errors", [])
    has_resume_turn = ctx.get("has_resume_turn", False)

    if "classification" in checks:
        expected = checks.get("classification")
        assert_one(expected == first_classification, f"classification mismatch: expected {expected}, got {first_classification}")

    if "classification_any" in checks:
        allowed = [str(x) for x in as_list(checks.get("classification_any"))]
        assert_one(first_classification in allowed, f"classification mismatch: expected one of {allowed}, got {first_classification}")

    if "classification_per_turn" in checks:
        expected_per_turn = as_list(checks.get("classification_per_turn"))
        assert_one(
            len(expected_per_turn) == len(classifications_by_turn),
            f"classification_per_turn length mismatch: expected {len(expected_per_turn)}, got {len(classifications_by_turn)}",
        )
        if len(expected_per_turn) == len(classifications_by_turn):
            for idx, (exp, got) in enumerate(zip(expected_per_turn, classifications_by_turn), start=1):
                assert_one(exp == got, f"turn {idx} classification mismatch: expected {exp}, got {got}")

    if "classification_per_turn_any" in checks:
        expected_per_turn_any = as_list(checks.get("classification_per_turn_any"))
        assert_one(
            len(expected_per_turn_any) == len(classifications_by_turn),
            f"classification_per_turn_any length mismatch: expected {len(expected_per_turn_any)}, got {len(classifications_by_turn)}",
        )
        if len(expected_per_turn_any) == len(classifications_by_turn):
            for idx, got in enumerate(classifications_by_turn, start=1):
                allowed = [str(x) for x in as_list(expected_per_turn_any[idx - 1])]
                assert_one(got in allowed, f"turn {idx} classification mismatch: expected one of {allowed}, got {got}")

    for tool_name in as_list(checks.get("required_tool_names")):
        assert_one(
            contains_normalized(tool_starts_normalized, str(tool_name)),
            f"missing tool_start containing: {tool_name}",
        )

    required_any = as_list(checks.get("required_tool_names_any"))
    if required_any:
        ok = any(contains_normalized(tool_starts_normalized, str(name)) for name in required_any)
        assert_one(ok, f"none of required_tool_names_any found: {required_any}")

    for tool_name in as_list(checks.get("forbidden_tool_names")):
        assert_one(
            not contains_normalized(tool_starts_normalized, str(tool_name)),
            f"forbidden tool_start found: {tool_name}",
        )

    for tool_name in as_list(checks.get("forbidden_tool_names_before_confirm")):
        assert_one(
            not contains_normalized(tool_starts_before_confirm_normalized, str(tool_name)),
            f"forbidden tool_start found before confirm: {tool_name}",
        )

    if "expect_confirm" in checks:
        expect_confirm = bool(checks.get("expect_confirm"))
        if expect_confirm:
            assert_one(bool(confirms), "expected confirm event but none found")
        else:
            assert_one(not bool(confirms), "confirm event found but expected none")

    if "expect_resume_turn" in checks:
        expect_resume_turn = bool(checks.get("expect_resume_turn"))
        if expect_resume_turn:
            assert_one(has_resume_turn, "expected resume turn but none found")
        else:
            assert_one(not has_resume_turn, "resume turn found but expected none")

    if "min_message_events" in checks:
        min_messages = int(checks.get("min_message_events", 0))
        assert_one(len(messages) >= min_messages, f"insufficient message events: expected >= {min_messages}, got {len(messages)}")

    if "min_tool_events" in checks:
        min_tool_events = int(checks.get("min_tool_events", 0))
        assert_one(
            len(tool_starts_normalized) >= min_tool_events,
            f"insufficient tool_start events: expected >= {min_tool_events}, got {len(tool_starts_normalized)}",
        )

    for needle in as_list(checks.get("message_contains")):
        n = str(needle).lower()
        assert_one(n in messages_joined_lower, f"message missing required text: {needle}")

    message_any_contains = as_list(checks.get("message_any_contains"))
    if message_any_contains:
        ok = any(str(needle).lower() in messages_joined_lower for needle in message_any_contains)
        assert_one(ok, f"message missing all alternatives in message_any_contains: {message_any_contains}")

    for needle in as_list(checks.get("message_not_contains")):
        n = str(needle).lower()
        assert_one(n not in messages_joined_lower, f"message contains forbidden text: {needle}")

    allow_error = bool(checks.get("allow_error", False))
    assert_one(allow_error or not errors, f"error events returned: {errors}")

    any_of = checks.get("any_of")
    if any_of:
        branches = as_list(any_of)
        branch_results: List[CheckEval] = []
        for idx, branch in enumerate(branches):
            if not isinstance(branch, dict):
                continue
            branch_results.append(evaluate_checks_block(branch, ctx, prefix=f"{prefix}any_of[{idx}]: "))

        if not branch_results:
            assert_one(False, "checks.any_of is set but no valid branch found")
        else:
            passing_branches = [br for br in branch_results if not br.failures]
            if passing_branches:
                best = max(passing_branches, key=lambda x: x.total_points)
                assert_one(True, "")
                passed_points += best.passed_points
                total_points += best.total_points
            else:
                best = min(branch_results, key=lambda x: (len(x.failures), -(x.passed_points / max(1, x.total_points))))
                assert_one(False, "none of checks.any_of branches passed")
                failures.extend(best.failures)
                passed_points += best.passed_points
                total_points += best.total_points

    return CheckEval(failures=failures, passed_points=passed_points, total_points=total_points)


def run_case(
    session: requests.Session,
    case: Dict[str, Any],
    max_side_effect: str,
    timeout_sec: int,
) -> CaseResult:
    case_id = case.get("id", "unknown")
    description = case.get("description", "")

    side_effect = case.get("side_effect", "low")
    if SIDE_EFFECT_LEVELS.get(side_effect, 1) > SIDE_EFFECT_LEVELS.get(max_side_effect, 3):
        return CaseResult(
            case_id=case_id,
            description=description,
            status="skipped",
            reasons=[f"side_effect={side_effect} exceeds max={max_side_effect}"],
            turns=[],
            rule_score=0.0,
            final_score=0.0,
            assertion_passed=0,
            assertion_total=0,
        )

    chat_id = case.get("chat_id") or str(uuid.uuid4())
    chat_url = SUT_BASE_URL.rstrip("/") + SUT_CHAT_PATH.format(id=chat_id)
    resume_url = SUT_BASE_URL.rstrip("/") + SUT_RESUME_PATH.format(id=chat_id)
    headers = build_sut_headers()

    turns = case.get("turns", [])
    hitl = case.get("hitl") or {}
    hitl_reply = hitl.get("on_confirm_user_reply")

    all_turns: List[TurnResult] = []
    has_resume = False

    for turn in turns:
        user_msg = turn.get("user", "")
        events, http_error = call_chat(
            session=session,
            url=chat_url,
            prompt=user_msg,
            model=SUT_MODEL,
            headers=headers,
            timeout_sec=timeout_sec,
        )
        if events is None:
            all_turns.append(TurnResult("chat", user_msg, [], {}, http_error))
            break

        summary = summarize_events(events)
        all_turns.append(TurnResult("chat", user_msg, events, summary, None))

        if hitl_reply and not has_resume and summary.get("confirms"):
            resume_events, resume_error = call_chat(
                session=session,
                url=resume_url,
                prompt=hitl_reply,
                model=SUT_MODEL,
                headers=headers,
                timeout_sec=timeout_sec,
            )
            if resume_events is None:
                all_turns.append(TurnResult("resume", hitl_reply, [], {}, resume_error))
                has_resume = True
                break

            resume_summary = summarize_events(resume_events)
            all_turns.append(TurnResult("resume", hitl_reply, resume_events, resume_summary, None))
            has_resume = True

    all_events = [e for t in all_turns for e in (t.events or [])]
    all_summary = summarize_events(all_events)
    raw_tool_starts = [str(x) for x in all_summary.get("tool_starts", []) if x is not None]
    raw_tool_starts_before_confirm = collect_tool_starts_before_confirm(all_events)
    tool_starts_normalized = [normalize_text(t) for t in raw_tool_starts]
    tool_starts_before_confirm_normalized = [normalize_text(t) for t in raw_tool_starts_before_confirm]
    messages = all_summary.get("messages", [])
    messages_joined_lower = "\n".join(messages).lower()
    confirms = all_summary.get("confirms", [])
    errors = all_summary.get("errors", [])

    classifications_by_turn: List[Optional[str]] = []
    for turn_result in all_turns:
        if turn_result.turn_type != "chat":
            continue
        classifications_by_turn.append(extract_classification(turn_result.summary.get("notifies", [])))

    ctx: Dict[str, Any] = {
        "first_classification": classifications_by_turn[0] if classifications_by_turn else None,
        "classifications_by_turn": classifications_by_turn,
        "tool_starts_normalized": tool_starts_normalized,
        "tool_starts_before_confirm_normalized": tool_starts_before_confirm_normalized,
        "messages": messages,
        "messages_joined_lower": messages_joined_lower,
        "confirms": confirms,
        "errors": errors,
        "has_resume_turn": any(t.turn_type == "resume" for t in all_turns),
    }

    checks = case.get("checks", {}) or {}
    check_eval = evaluate_checks_block(checks, ctx)
    reasons: List[str] = list(check_eval.failures)

    rule_score = 100.0
    if check_eval.total_points > 0:
        rule_score = round(100.0 * check_eval.passed_points / check_eval.total_points, 2)

    judge_result: Optional[Dict[str, Any]] = None
    judge_score: Optional[float] = None
    if case.get("judge", {}).get("enabled"):
        judge_result = run_judge(case, all_turns)
        error_text = judge_result.get("error") if judge_result else None
        if error_text:
            reasons.append(str(error_text))
        elif not judge_result.get("skipped"):
            verdict = str(judge_result.get("verdict", "")).lower()
            if verdict and verdict != "pass":
                reasons.append("judge_verdict_fail")
            try:
                score_raw = judge_result.get("score")
                judge_score = float(score_raw) if score_raw is not None else None
            except (TypeError, ValueError):
                judge_score = None

    final_score = rule_score
    if judge_score is not None:
        judge_pct = max(0.0, min(100.0, judge_score * 20.0))
        final_score = round(rule_score * 0.7 + judge_pct * 0.3, 2)

    status = "pass" if not reasons else "fail"
    return CaseResult(
        case_id=case_id,
        description=description,
        status=status,
        reasons=reasons,
        turns=all_turns,
        judge=judge_result,
        rule_score=rule_score,
        final_score=final_score,
        judge_score=judge_score,
        assertion_passed=check_eval.passed_points,
        assertion_total=check_eval.total_points,
    )


def write_txt_report(
    path: str,
    case_results: List[CaseResult],
    total: int,
    passed: int,
    failed: int,
    skipped: int,
) -> None:
    scored = [r.final_score for r in case_results if r.status != "skipped"]
    overall_score = round(sum(scored) / len(scored), 2) if scored else 0.0

    lines: List[str] = []
    lines.append("RideAgentHub Evaluation Report")
    lines.append(f"Generated At: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"SUT Base URL: {SUT_BASE_URL}")
    lines.append(f"SUT Model: {SUT_MODEL}")
    lines.append("-")
    lines.append(f"Total: {total}")
    lines.append(f"Passed: {passed}")
    lines.append(f"Failed: {failed}")
    lines.append(f"Skipped: {skipped}")
    lines.append(f"Overall Score (0-100): {overall_score}")
    lines.append("=")

    for r in case_results:
        lines.append(f"[{r.status.upper()}] {r.case_id}")
        lines.append(f"Description: {r.description}")
        lines.append(
            f"Scores: final={r.final_score:.2f}, rule={r.rule_score:.2f}, "
            f"judge={(f'{r.judge_score:.2f}' if r.judge_score is not None else 'N/A')}"
        )
        lines.append(f"Assertions: {r.assertion_passed}/{r.assertion_total}")
        if r.reasons:
            lines.append("Reasons:")
            for reason in r.reasons:
                lines.append(f"- {reason}")
        lines.append("-")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def main() -> int:
    parser = argparse.ArgumentParser(description="LLM-as-judge evaluation runner")
    parser.add_argument("--cases", default="src/test/llm_judge_cases.jsonl", help="Path to JSONL cases")
    parser.add_argument("--out", default="", help="Optional JSON output path")
    parser.add_argument("--report-txt", default="src/test/llm_judge_report.txt", help="Text report output path")
    parser.add_argument("--max-side-effect", default="high", choices=["low", "medium", "high"])
    parser.add_argument("--timeout", type=int, default=180, help="Per-call timeout in seconds")
    args = parser.parse_args()

    cases = load_cases(args.cases)
    if not cases:
        print("No cases loaded", file=sys.stderr)
        return 2

    results_json: List[Dict[str, Any]] = []
    case_results: List[CaseResult] = []
    passed = failed = skipped = 0

    with requests.Session() as session:
        for case in cases:
            case_result = run_case(session, case, args.max_side_effect, args.timeout)
            case_results.append(case_result)

            if case_result.status == "pass":
                passed += 1
            elif case_result.status == "fail":
                failed += 1
            else:
                skipped += 1

            results_json.append(
                {
                    "id": case_result.case_id,
                    "description": case_result.description,
                    "status": case_result.status,
                    "final_score": case_result.final_score,
                    "rule_score": case_result.rule_score,
                    "judge_score": case_result.judge_score,
                    "assertions": {
                        "passed": case_result.assertion_passed,
                        "total": case_result.assertion_total,
                    },
                    "reasons": case_result.reasons,
                    "judge": case_result.judge,
                }
            )

            print(
                f"[{case_result.status.upper()}] {case_result.case_id} "
                f"final={case_result.final_score:.2f}"
            )
            if case_result.reasons:
                for reason in case_result.reasons:
                    print(f"  - {reason}")

            time.sleep(0.2)

    total = passed + failed + skipped
    scored = [r.final_score for r in case_results if r.status != "skipped"]
    overall_score = round(sum(scored) / len(scored), 2) if scored else 0.0
    print(
        f"\nTotal: {total} | Passed: {passed} | Failed: {failed} | "
        f"Skipped: {skipped} | Overall Score: {overall_score}"
    )

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "meta": {
                        "total": total,
                        "passed": passed,
                        "failed": failed,
                        "skipped": skipped,
                        "overall_score": overall_score,
                    },
                    "results": results_json,
                },
                f,
                indent=2,
                ensure_ascii=False,
            )

    if args.report_txt:
        write_txt_report(args.report_txt, case_results, total, passed, failed, skipped)
        print(f"Text report written to: {args.report_txt}")

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
