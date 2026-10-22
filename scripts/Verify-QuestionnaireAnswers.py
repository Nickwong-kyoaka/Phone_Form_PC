#!/usr/bin/env python3
"""Compare frozen, independently read truth with raw production candidates.

This never reads a PDF, changes a profile, or writes human answers. Private
per-target values go only to --private-output; --summary contains counts only.
Matrix rows are separate targets. Unsupported/manual fields remain visible in
the full denominator. Precision is undefined when no candidate was produced.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def same_answer(truth, candidate):
    if candidate.get("state") != truth["state"]:
        return False
    if truth["state"] != "VALUE":
        return True
    expected = truth.get("optionIds") or []
    actual = candidate.get("optionIds") or []
    if expected or actual:
        # Duplicate IDs are invalid, even when their set would compare equal.
        return len(actual) == len(set(actual)) and set(actual) == set(expected)
    # No stripping, numeric coercion or fuzzy matching: retain leading zeros.
    return candidate.get("text") == truth.get("text")


def summarize(rows):
    counts = Counter()
    reasons = Counter()
    for row in rows:
        counts["targets"] += 1
        counts["configuredAutomatic" if row["automaticEnabled"] else "configuredManual"] += 1
        counts[row["outcome"]] += 1
        if row["scorable"]:
            counts["scorable"] += 1
            if row["candidate"] is not None:
                counts["candidates"] += 1
            if row["truth"]["state"] == "BLANK":
                counts["truthBlank"] += 1
                if row["candidate"] and row["candidate"].get("state") == "VALUE":
                    counts["blankFalseSelected"] += 1
            if row["truth"]["state"] == "VALUE" and row["candidate"] and row["candidate"].get("state") == "BLANK":
                counts["valueFalseBlank"] += 1
        else:
            counts["uncertainTruth"] += 1
            if row["candidate"] is not None:
                counts["candidateOnUncertainTruth"] += 1
        if row["reason"]:
            reasons[row["reason"]] += 1
    result = {key: counts[key] for key in (
        "targets", "scorable", "uncertainTruth", "configuredAutomatic", "configuredManual",
        "candidates", "correct", "wrong", "abstained", "unscored",
        "truthBlank", "blankFalseSelected", "valueFalseBlank", "candidateOnUncertainTruth")}
    result["candidatePrecisionPercent"] = round(100 * counts["correct"] / counts["candidates"], 2) if counts["candidates"] else None
    result["candidateCoveragePercent"] = round(100 * counts["candidates"] / counts["scorable"], 2) if counts["scorable"] else None
    result["correctOfScorablePercent"] = round(100 * counts["correct"] / counts["scorable"], 2) if counts["scorable"] else None
    result["reasons"] = dict(reasons.most_common())
    return result


def compare(raw, truth_documents):
    truth_map = {t["documentId"]: t for t in truth_documents}
    if len(truth_map) != len(truth_documents):
        raise ValueError("Duplicate truth document ID")
    if set(truth_map) != {d["id"] for d in raw["documents"]}:
        raise ValueError("Raw/truth document sets differ")
    all_rows, documents = [], []
    for document in raw["documents"]:
        truth = truth_map[document["id"]]
        if document["pdfSha256"] != truth.get("sourceSha256", truth.get("pdfSha256")):
            raise ValueError("Raw/truth source PDF fingerprint differs")
        run = document["calibrated"]
        if run.get("error"):
            raise ValueError("Production run did not finish: " + str(run["error"]))
        if run.get("answers") is not None or run.get("confirmation") is not None:
            raise ValueError("Human answers/confirmation contaminated the measurement")
        job = run.get("job") or {}
        if job.get("status") != "READY":
            raise ValueError("Recognition job is not READY")
        questions = {q["id"]: q for q in run["questions"]}
        targets = {t["id"]: t for t in truth["targets"]}
        if len(targets) != len(truth["targets"]) or set(targets) != set(questions):
            raise ValueError("Question/truth coverage differs for " + document["id"])
        observations = {o["targetKey"]: o for o in job.get("observations", [])}
        if len(observations) != len(job.get("observations", [])):
            raise ValueError("Duplicate observation target")
        if set(observations) != {"question:" + key for key in targets}:
            raise ValueError("Missing or extra production observations")
        settings = {s["questionId"]: s for s in run["profile"]["settings"]}
        if set(settings) != set(targets):
            raise ValueError("Profile/target coverage differs")
        rows = []
        for target_id, target in targets.items():
            observation = observations["question:" + target_id]
            candidate = observation.get("candidate")
            scorable = target["state"] in ("VALUE", "BLANK", "NOT_APPLICABLE", "NOT_CAPTURED") and target.get("certainty", "HIGH") == "HIGH"
            outcome = "unscored" if not scorable else "abstained" if candidate is None else "correct" if same_answer(target, candidate) else "wrong"
            question, setting = questions[target_id], settings[target_id]
            enabled = bool(setting.get("enabled")) and question["type"] != "TEXT" and not (question["type"] in ("SINGLE_CHOICE", "MULTIPLE_CHOICE") and setting.get("style") == "MANUAL_ONLY")
            rows.append({"documentId": document["id"], "id": target_id, "page": target["page"],
                         "category": target["category"], "label": question.get("label", target_id),
                         "scorable": scorable, "automaticEnabled": enabled, "outcome": outcome,
                         "truth": target, "candidate": candidate, "reason": observation.get("reason", ""),
                         "rawText": observation.get("rawText"), "enhancedRawText": observation.get("enhancedRawText"),
                         "optionReasons": [dict(optionId=o["optionId"], decision=o["decision"], reason=o.get("reason", "")) for o in observation.get("optionObservations", [])]})
        grouped = defaultdict(list)
        for row in rows:
            grouped[row["category"]].append(row)
        documents.append({"id": document["id"], "pageCount": document["pageCount"], "totals": summarize(rows),
                          "automaticEnabledSubset": summarize([r for r in rows if r["automaticEnabled"]]),
                          "manualSubset": summarize([r for r in rows if not r["automaticEnabled"]]),
                          "byType": {key: summarize(group) for key, group in sorted(grouped.items())},
                          "automaticQuestionCounts": [{"page": page["page"], "count": len(page.get("questions", [])), "error": page.get("error")} for page in document.get("automatic", [])],
                          "alignmentCounts": dict(Counter(a["status"] for a in job.get("alignments", []))),
                          "exclusionCount": len(truth.get("exclusions", []))})
        all_rows.extend(rows)
    grouped = defaultdict(list)
    for row in all_rows:
        grouped[row["category"]].append(row)
    summary = {"result": "MEASURED", "appVersion": raw["appVersion"], "engineVersion": raw["engineVersion"],
               "parametersVersion": raw["parametersVersion"], "checkedAt": datetime.now(timezone.utc).isoformat(),
               "conditions": raw.get("conditions", []), "totals": summarize(all_rows),
               "automaticEnabledSubset": summarize([r for r in all_rows if r["automaticEnabled"]]),
               "manualSubset": summarize([r for r in all_rows if not r["automaticEnabled"]]),
               "byType": {key: summarize(group) for key, group in sorted(grouped.items())}, "documents": documents,
               "definitions": {"candidatePrecisionPercent": "correct / candidates, only independently scorable targets; null if no candidates",
                               "candidateCoveragePercent": "candidates / independently scorable targets, including manual fields in denominator",
                               "correctOfScorablePercent": "correct / independently scorable targets, including abstentions",
                               "matrixUnit": "one row, not one entire matrix",
                               "uncertainTruth": "excluded from exact-answer percentages; candidate production is separately reported",
                               "automaticQuestionCounts": "raw detected suggestions; not matched question accuracy"},
               "nativeExcelOpened": False, "humanAnswersWritten": False,
               "realScanGeneralizationEstablished": False}
    if raw.get("sourceRuns"):
        summary["executionRuns"] = raw["sourceRuns"]
        if any(run.get("processExitCode") != 0 for run in raw["sourceRuns"]):
            summary["result"] = "MEASURED_WITH_PROCESS_FAILURE"
    summary["parametersFingerprint"] = raw.get("parametersFingerprint")
    return summary, all_rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path)
    parser.add_argument("truth", nargs="+", type=Path)
    parser.add_argument("--summary", required=True, type=Path)
    parser.add_argument("--private-output", required=True, type=Path)
    args = parser.parse_args()
    private_root = Path(__file__).resolve().parent.parent / ".local"
    if not args.private_output.resolve().is_relative_to(private_root):
        parser.error("Private per-target output must stay inside this repository's ignored .local directory")
    summary, rows = compare(load(args.raw), [load(p) for p in args.truth])
    summary["rawReportSha256"] = sha(args.raw)
    summary["truthSha256"] = [sha(p) for p in args.truth]
    for path, value in ((args.summary, summary), (args.private_output, {"summary": summary, "targets": rows})):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"totals": summary["totals"], "byType": summary["byType"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
