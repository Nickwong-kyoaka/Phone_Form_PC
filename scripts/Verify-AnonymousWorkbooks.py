#!/usr/bin/env python3
"""Independently audit anonymous PhoneForm QA exports using only Python's stdlib.

Usage: python scripts/Verify-AnonymousWorkbooks.py QA_DIRECTORY EXPECTED_ROWS
       [--profile auto|recognition|scale] [--output REPORT.json]

Reads the three review-*.xlsx files without rewriting them. Expected answers and
headers below are the public anonymous fixture contract, not app XLSX code.
Only counts, error codes and file SHA-256 values leave the in-memory audit.
This checks OOXML data, not native Excel display or OCR/general-scan accuracy.
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import posixpath
import re
import sys
import xml.etree.ElementTree as ET
import zipfile


NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
DOC_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
SHEETS = ["Responses", "Codebook", "Review_Flags", "Summary"]
SYSTEM_KEYS = ["submission_id", "record_id", "review_status", "submission_revision",
               "template_version", "completeness"]
MISSING = "BLANK;NOT_APPLICABLE;UNREADABLE;NOT_CAPTURED;UNHANDLED"
HEADERS = {
    "ENGLISH": {
        "Responses": ["Questionnaire number", "Record ID", "Review status", "Submission revision", "Template version", "Page completeness"],
        "Codebook": ["Column key", "Title", "Target key", "Type", "Option code", "Option title", "Codes", "Multiple choice rules", "Missing states"],
        "Review_Flags": ["Record ID", "Column key", "State", "Source", "Reason"],
        "Summary": ["Key", "Value"],
    },
    "SIMPLIFIED": {
        "Responses": ["问卷编号", "内部记录 ID", "覆核状态", "提交修订", "模板版本", "页面完整性"],
        "Codebook": ["列键", "标题", "目标键", "类型", "选项代码", "选项标题", "代码", "多选规则", "缺失状态"],
        "Review_Flags": ["内部记录 ID", "列键", "状态", "来源", "原因"],
        "Summary": ["键", "值"],
    },
    "TRADITIONAL": {
        "Responses": ["問卷編號", "內部記錄 ID", "覆核狀態", "提交修訂", "範本版本", "頁面完整性"],
        "Codebook": ["欄鍵", "標題", "目標鍵", "類型", "選項代碼", "選項標題", "代碼", "多選規則", "缺失狀態"],
        "Review_Flags": ["內部記錄 ID", "欄鍵", "狀態", "來源", "原因"],
        "Summary": ["鍵", "值"],
    },
}


@dataclass(frozen=True)
class Cell:
    value: str = ""
    kind: str = "blank"

    @property
    def is_text(self) -> bool:
        return self.kind in {"inlineStr", "s", "str"}


class InputError(Exception):
    """Carries an anonymous error code, never file/cell contents."""


class Checks:
    def __init__(self) -> None:
        self.total = 0
        self.errors: Counter[str] = Counter()

    def check(self, condition: bool, code: str) -> None:
        self.total += 1
        if not condition:
            self.errors[code] += 1


def values(row: list[Cell]) -> list[str]:
    return [cell.value for cell in row]


def col_index(address: str) -> int:
    match = re.fullmatch(r"([A-Z]+)([1-9][0-9]*)", address)
    if match is None:
        raise InputError("INVALID_CELL_ADDRESS")
    number = 0
    for char in match[1]:
        number = number * 26 + ord(char) - ord("A") + 1
    return number - 1


def read_workbook(path: Path) -> tuple[dict[str, list[list[Cell]]], int, str]:
    original = path.read_bytes()
    digest = hashlib.sha256(original).hexdigest()
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise InputError("ZIP_CRC_FAILURE")
        shared: list[str] = []
        if "xl/sharedStrings.xml" in archive.namelist():
            shared = ["".join(node.itertext()) for node in ET.fromstring(archive.read("xl/sharedStrings.xml"))]
        relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        targets = {r.attrib["Id"]: r.attrib["Target"] for r in relationships
                   if r.attrib.get("TargetMode") != "External"}
        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        names = workbook.find("m:sheets", NS)
        if names is None or [s.attrib.get("name") for s in names] != SHEETS:
            raise InputError("FOUR_SHEET_NAMES_OR_ORDER")
        result: dict[str, list[list[Cell]]] = {}
        formula_count = 0
        for sheet in names:
            target = targets[sheet.attrib[f"{{{DOC_REL}}}id"]]
            entry = posixpath.normpath(target.lstrip("/") if target.startswith("/") else posixpath.join("xl", target))
            if not entry.startswith("xl/"):
                raise InputError("INVALID_SHEET_RELATIONSHIP")
            xml = ET.fromstring(archive.read(entry))
            formula_count += len(xml.findall(".//m:f", NS))
            rows: list[list[Cell]] = []
            last_row = 0
            for row in xml.findall("m:sheetData/m:row", NS):
                number = int(row.attrib["r"])
                if number != last_row + 1:
                    raise InputError("NONCONTIGUOUS_QA_ROWS")
                last_row = number
                cells: dict[int, Cell] = {}
                for cell in row.findall("m:c", NS):
                    index = col_index(cell.attrib["r"])
                    if index in cells or int(re.search(r"[0-9]+$", cell.attrib["r"])[0]) != number:
                        raise InputError("DUPLICATE_OR_MISPLACED_CELL")
                    kind = cell.attrib.get("t", "n")
                    raw = cell.findtext("m:v", default="", namespaces=NS)
                    if kind == "inlineStr":
                        text = "".join(t.text or "" for t in cell.findall("m:is//m:t", NS))
                    elif kind == "s":
                        text = shared[int(raw)]
                    else:
                        text = raw
                    cells[index] = Cell(text, kind)
                width = max(cells, default=-1) + 1
                rows.append([cells.get(index, Cell()) for index in range(width)])
            result[sheet.attrib["name"]] = rows
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise InputError("INPUT_CHANGED_DURING_READ")
    return result, formula_count, digest


def padded(row: list[Cell], width: int) -> list[Cell]:
    return row + [Cell()] * max(0, width - len(row))


def audit(tables: dict[str, list[list[Cell]]], language: str, expected_rows: int,
          requested_profile: str, formulas: int, digest: str) -> tuple[dict, tuple]:
    checks = Checks()
    checks.check(formulas == 0, "CELL_FORMULAS_PRESENT")
    checks.check(all(cell.is_text or cell.value == "" for rows in tables.values() for row in rows for cell in row),
                 "ANONYMOUS_TEXT_FIXTURE_CELL_STORAGE")
    if any(not tables[name] for name in SHEETS):
        raise InputError("MISSING_SHEET_HEADER")
    response_header = values(tables["Responses"][0])
    for name in SHEETS[1:]:
        checks.check(values(tables[name][0]) == HEADERS[language][name], "LOCALIZED_" + name.upper() + "_HEADER")
    checks.check(response_header[:6] == HEADERS[language]["Responses"], "LOCALIZED_RESPONSES_HEADER")
    codebook = [values(padded(row, 9)) for row in tables["Codebook"][1:]]
    checks.check(len(codebook) == len(response_header), "CODEBOOK_COLUMN_COUNT")
    keys = [row[0] for row in codebook]
    checks.check(len(set(keys)) == len(keys), "DUPLICATE_CODEBOOK_KEYS")
    checks.check(keys[:6] == SYSTEM_KEYS, "FIXED_COLUMN_KEY_ORDER")
    checks.check([row[1] for row in codebook] == response_header, "CODEBOOK_RESPONSE_TITLE_MAPPING")
    checks.check(all(row[8] == MISSING for row in codebook), "MISSING_STATE_DICTIONARY")
    checks.check(all(row[2] == "" and row[3] == "SYSTEM" and not any(row[4:8]) for row in codebook[:6]), "SYSTEM_CODEBOOK_MAPPING")
    target_set = {row[2] for row in codebook[6:]}
    inferred = ("recognition" if target_set == {"question:choice", "question:text", "question:circle"}
                else "scale" if target_set == {f"question:q{i}" for i in range(1, 10)} else "unknown")
    profile = inferred if requested_profile == "auto" else requested_profile
    checks.check(profile == inferred and inferred != "unknown", "ANONYMOUS_FIXTURE_PROFILE")
    expected_titles = (["Select a box", "Exact text", "Circled number"] if profile == "recognition"
                       else [f"題目 {i} / Question {i}" for i in range(1, 10)])
    checks.check(response_header[6:] == expected_titles, "PRINTED_TITLES_NOT_TRANSLATED")
    expected_targets = (["choice", "text", "circle"] if profile == "recognition" else [f"q{i}" for i in range(1, 10)])
    for position, row in enumerate(codebook[6:]):
        if position >= len(expected_targets):
            checks.check(False, "UNEXPECTED_CODEBOOK_COLUMN")
            continue
        identifier = expected_targets[position]
        checks.check(row[0] == f"question_{identifier}" and row[2] == f"question:{identifier}", "STABLE_TARGET_COLUMN_MAPPING")
        expected_type = "SINGLE_CHOICE" if profile == "recognition" and identifier in {"choice", "circle"} else "TEXT"
        checks.check(row[3] == expected_type and not any(row[4:6]) and row[7] == "", "CODEBOOK_QUESTION_TYPE")
        codes = "0=A | 01=B" if identifier == "choice" else "0=0 | 1=1" if identifier == "circle" else ""
        checks.check(row[6] == codes, "OPTION_CODE_TITLE_MAPPING")
    responses = [padded(row, len(response_header)) for row in tables["Responses"][1:]]
    checks.check(len(responses) == expected_rows, "RESPONSE_ROW_COUNT")
    records: dict[str, list[Cell]] = {}
    ordinal_numbers: list[int] = []
    literal_count = leading_zero_cells = 0
    for row in responses:
        checks.check(len(row) == len(response_header), "RESPONSE_ROW_WIDTH")
        if len(row) < 6:
            continue
        checks.check(all(cell.is_text for cell in row), "RESPONSE_TEXT_STORAGE")
        checks.check(row[1].value != "" and row[1].value not in records, "RECORD_ID_UNIQUE")
        records[row[1].value] = row
        number = int(row[0].value) if row[0].value.isdecimal() else -1
        ordinal_numbers.append(number)
        checks.check(row[2].value == "CONFIRMED" and row[5].value == "COMPLETE", "REVIEW_AND_COMPLETENESS")
        checks.check(bool(row[3].value) and bool(row[4].value), "REVISION_TEMPLATE_REFERENCES")
        expected = (["0", "=1+1", "0"] if profile == "recognition" else
                    [f"{number:05d}"] + [f"答案 {number - 1} / answer {i}" for i in range(1, 8)] + ["=1+1"])
        checks.check(values(row[6:]) == expected, "ANONYMOUS_EXPECTED_ANSWERS")
        literal_count += sum(cell.value == "=1+1" and cell.is_text for cell in row[6:])
        leading_zero_cells += sum(bool(re.fullmatch(r"0[0-9]+", cell.value)) and cell.is_text for cell in row[6:])
    checks.check(sorted(ordinal_numbers) == list(range(1, expected_rows + 1)), "ORDINAL_RECORD_COVERAGE")
    checks.check(literal_count == expected_rows, "LITERAL_FORMULA_TEXT_COVERAGE")
    if profile == "scale":
        checks.check(leading_zero_cells == expected_rows, "LEADING_ZERO_RESPONSE_COVERAGE")
    checks.check(len({row[3].value for row in responses}) == len(responses), "REVISION_REFERENCE_UNIQUE")
    checks.check(len({row[4].value for row in responses}) == 1, "TEMPLATE_REFERENCE_CONSISTENT")
    flags = [values(padded(row, 5)) for row in tables["Review_Flags"][1:]]
    for row in flags:
        checks.check(len(row) == 5, "REVIEW_FLAG_WIDTH")
        checks.check(row[0] in records, "REVIEW_FLAG_RECORD_REFERENCE")
        checks.check(row[1] in keys, "REVIEW_FLAG_COLUMN_REFERENCE")
        checks.check(row[2] == "PAGE_QUALITY_KEPT" and row[3] == "IMAGE_PROCESSING" and row[1] == "completeness", "CONFIRMED_FIXTURE_FLAG_STATE")
    summary_rows = [values(padded(row, 2)) for row in tables["Summary"][1:]]
    checks.check(all(len(row) == 2 for row in summary_rows), "SUMMARY_ROW_WIDTH")
    summary = dict(summary_rows)
    checks.check(len(summary) == len(summary_rows), "SUMMARY_UNIQUE_KEYS")
    checks.check(summary.get("included") == str(expected_rows), "SUMMARY_INCLUDED_RECONCILIATION")
    checks.check(summary.get("excluded") == "0" and not any(key.startswith("excluded:") for key in summary), "SUMMARY_EXCLUDED_RECONCILIATION")
    checks.check(summary.get("include_unresolved") == "false" and summary.get("include_personal_identifiers") == "false", "SUMMARY_PRIVACY_POLICY")
    expected_project = "Anonymous recognition / 匿名辨識" if profile == "recognition" else "中英問卷 / Bilingual questionnaire"
    checks.check(summary.get("project") == expected_project, "SUMMARY_ANONYMOUS_PROJECT_TEXT")
    checks.check(summary.get("snapshot_id") == f"snapshot:{language}" and summary.get("output_version", "").isdecimal(), "SUMMARY_SNAPSHOT_VERSION")
    checks.check(all("\ufffd" not in cell.value for rows in tables.values() for row in rows for cell in row), "UNICODE_REPLACEMENT_CHARACTERS")
    checks.check("respondent_id" not in keys, "PERSONAL_IDENTIFIER_COLUMN_EXCLUDED")
    leading_zero_codes = sum(bool(re.search(r"(?:^| \| )0[0-9]+=", row[6])) for row in codebook)
    if profile == "recognition":
        checks.check(leading_zero_codes == 1, "LEADING_ZERO_CODEBOOK_COVERAGE")
    public = {"language": language, "profile": profile, "sha256": digest, "responseRows": len(responses),
              "responseColumns": len(response_header), "codebookRows": len(codebook), "reviewFlagRows": len(flags),
              "cellFormulaCount": formulas, "literalFormulaTextCells": literal_count,
              "leadingZeroResponseCells": leading_zero_cells, "leadingZeroCodebookMappings": leading_zero_codes,
              "checks": checks.total, "errors": dict(sorted(checks.errors.items())), "passed": not checks.errors}
    # IDs/answers are used only for equality in memory, never included in the report.
    semantics = (tuple(keys), tuple(sorted(tuple(values(row)) for row in responses)),
                 tuple(sorted(tuple(row) for row in flags)), tuple(tuple(row[2:]) for row in codebook))
    return public, semantics


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("qa_directory", type=Path)
    parser.add_argument("expected_rows", type=int)
    parser.add_argument("--profile", choices=["auto", "recognition", "scale"], default="auto")
    parser.add_argument("--output", type=Path, help="Optional aggregate JSON report; stdout always receives the same report")
    args = parser.parse_args()
    if args.expected_rows < 1:
        parser.error("EXPECTED_ROWS must be positive")
    if args.output and args.output.suffix.lower() != ".json":
        parser.error("--output must name a JSON report; workbook files are never overwritten")
    results: list[dict] = []
    semantic_results: list[tuple] = []
    for language in HEADERS:
        try:
            tables, formulas, digest = read_workbook(args.qa_directory / f"review-{language}.xlsx")
            public, semantics = audit(tables, language, args.expected_rows, args.profile, formulas, digest)
            results.append(public)
            semantic_results.append(semantics)
        except InputError as error:
            results.append({"language": language, "passed": False, "errors": {str(error): 1}})
        except (OSError, ValueError, KeyError, IndexError, zipfile.BadZipFile, ET.ParseError):
            results.append({"language": language, "passed": False, "errors": {"INVALID_OR_UNAVAILABLE_OOXML": 1}})
    same = len(semantic_results) == 3 and all(value == semantic_results[0] for value in semantic_results[1:])
    report = {"schemaVersion": 1, "dateUtc": datetime.now(timezone.utc).isoformat(), "expectedRowsPerWorkbook": args.expected_rows,
              "scope": "Independent stdlib OOXML data audit of known anonymous fixtures; no native Excel/UI verification",
              "crossLanguageDataIdentical": same, "passed": same and all(item["passed"] for item in results), "workbooks": results}
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    sys.stdout.write(output)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
