#!/usr/bin/env python3
"""Architecture fitness functions F1-F10 as ratchets (docs/system-architecture.md v2, section 3.2).

Every check counts violations per key (check/repository). CI fails when a count is above its baseline in
baseline.json; a count below the baseline is reported so the baseline can be lowered in the same change.
The baseline only goes down: --write-baseline refuses to raise a key unless --allow-increase is given (and the
increase then shows in review as a baseline.json diff).

    fitness.py                      check against baseline.json (exit 1 on any increase)
    fitness.py --list F4            print every counted violation of F4 (file:line: detail)
    fitness.py --write-baseline     store the current counts (lowering only)

Offline stand-ins: ArchUnit, Maven Enforcer and import-linter are not available offline yet, so F1/F2/F3/F7/F8
are source scans with the same intent. F6 and F10 have no subject yet (core DDL in P3, fact emitters in P3);
they are listed with baseline 0 and become real checks with their subjects.
Standard library only; WORKSPACE (default: two levels above this file's repo) points at the repositories.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASELINE = HERE / "baseline.json"
WORKSPACE = Path(os.environ.get("WORKSPACE", HERE.parents[2]))

# control core and device repositories: no simulation concepts (F4), injected clock (F7), no static state (F8)
CORE_JAVA = ["salasim_gmpls_pce", "salasim_gmpls_emulator", "salasim_gmpls_topology", "salasim_gmpls_protocols",
             "salasim_gmpls_netconf", "salasim_gmpls_controller"]
YANG_REPO = "salasim_gmpls_yang"
BACKEND_PKG_ROOT = "salasim_gmpls_backend/src"

Violation = tuple[str, str]  # (key, "path:line: detail")


def rel(p: Path) -> str:
    return str(p.relative_to(WORKSPACE))


def java_sources(repo: str, main_only: bool = True):
    root = WORKSPACE / repo
    for p in sorted(root.rglob("*.java")):
        parts = p.relative_to(root).parts
        if "target" in parts or "test" in parts and main_only:
            continue
        yield p


def strip_java_comments(text: str) -> str:
    """Blank out // and /* */ comments, keep string literals (JSON field names are symbols too) and line numbers."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"' and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1]); i = j + 1
        elif text.startswith("//", i):
            j = text.find("\n", i); j = n if j < 0 else j
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2); j = n if j < 0 else j + 2
            out.append("\n" * text.count("\n", i, j)); i = j
        else:
            out.append(c); i += 1
    return "".join(out)


def strip_yang_strings(text: str) -> str:
    """Blank out quoted strings and comments (descriptions are prose, node names are unquoted)."""
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group().count("\n"), text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return re.sub(r'"(?:\\.|[^"\\])*"', lambda m: "\n" * m.group().count("\n"), text, flags=re.S)


# ---------------------------------------------------------------- F4 simulation vocabulary (symbol based)

# matched against the identifier with '-' and '_' removed, lower-cased
F4_TERMS = ["simulation", "speedup", "simtime", "runid", "runepoch", "frameindex", "frameidx", "frameid",
            "framenumber", "framenum", "snapshotindex", "futureframe", "loadframe", "currentframe", "frametransition",
            "salasimframe", "framewindow", "lookaheadframes", "clockanchor", "topologyclock"]
# 'Sim' as its own word at the start of a camel/snake/kebab identifier: SimFramesHandler, sim_time, sim-time
F4_SIM_PREFIX = re.compile(r"^(?:(?:Sim|sim)(?:[A-Z_\-]|$)|SIM(?:_|$))")
IDENT_JAVA = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
IDENT_YANG = re.compile(r"[A-Za-z_][A-Za-z0-9_.\-]*")
F4_URL = re.compile(r"/sim/")


def f4_hit(ident: str) -> str | None:
    norm = ident.replace("-", "").replace("_", "").lower()
    for t in F4_TERMS:
        if t in norm:
            return t
    if F4_SIM_PREFIX.match(ident):
        return "sim"
    return None


def scan_idents(path: Path, text: str, ident_re: re.Pattern, key: str, out: list[Violation]):
    for lineno, line in enumerate(text.splitlines(), 1):
        for m in ident_re.finditer(line):
            t = f4_hit(m.group())
            if t:
                out.append((key, f"{rel(path)}:{lineno}: {m.group()} [{t}]"))
        if F4_URL.search(line):
            out.append((key, f"{rel(path)}:{lineno}: /sim/ path"))


def yang_classes() -> dict[str, str]:
    classes = {}
    for line in (WORKSPACE / YANG_REPO / "module-classes.txt").read_text().splitlines():
        line = line.split("#", 1)[0].split()
        if len(line) == 2:
            classes[line[0]] = line[1]
    return classes


def check_f4() -> list[Violation]:
    out: list[Violation] = []
    for repo in CORE_JAVA:
        for p in java_sources(repo):
            scan_idents(p, strip_java_comments(p.read_text(errors="replace")), IDENT_JAVA, f"F4/{repo}", out)
    for m, cls in yang_classes().items():
        if cls != "sim":
            p = WORKSPACE / YANG_REPO / "salasim" / f"{m}.yang"
            scan_idents(p, strip_yang_strings(p.read_text()), IDENT_YANG, f"F4/{YANG_REPO}", out)
    return out


# ---------------------------------------------------------------- F5 core YANG must not import sim YANG

def check_f5() -> list[Violation]:
    out: list[Violation] = []
    classes = yang_classes()
    for m, cls in classes.items():
        if cls == "sim":
            continue
        p = WORKSPACE / YANG_REPO / "salasim" / f"{m}.yang"
        for lineno, line in enumerate(p.read_text().splitlines(), 1):
            mm = re.match(r"\s*import\s+([A-Za-z0-9_.\-]+)", line)
            if mm and classes.get(mm.group(1)) == "sim":
                out.append((f"F5/{YANG_REPO}", f"{rel(p)}:{lineno}: {m} ({cls}) imports {mm.group(1)} (sim)"))
    return out


# ---------------------------------------------------------------- F1 artifact dependency direction

ARTIFACT_REPO = {"salasim-yang": "yang", "network-protocols": "protocols", "topology": "topology", "pce": "pce",
                 "salasim-netconf-mgmt": "netconf", "network-emulator": "emulator", "salasim-controller": "controller"}
# allowed internal dependencies (system-architecture v2 section 3.1: libraries yang <- protocols <- topology <- pce;
# devices use protocols/topology/netconf/yang; controller uses netconf/yang; nothing depends on a service)
F1_ALLOWED = {
    "yang": set(),
    "protocols": {"yang"},
    "netconf": {"yang"},
    "topology": {"yang", "protocols"},
    "pce": {"yang", "protocols", "topology", "netconf"},
    "emulator": {"yang", "protocols", "topology", "netconf"},
    "controller": {"yang", "netconf"},
}


def check_f1() -> list[Violation]:
    out: list[Violation] = []
    for repo, allowed in F1_ALLOWED.items():
        pom = WORKSPACE / f"salasim_gmpls_{repo}" / "pom.xml"
        text = re.sub(r"<!--.*?-->", "", pom.read_text(), flags=re.S)
        text = re.sub(r"<parent>.*?</parent>", "", text, flags=re.S)
        for block in re.findall(r"<dependency>(.*?)</dependency>", text, flags=re.S):
            a = re.search(r"<artifactId>\s*([^<\s]+)\s*</artifactId>", block)
            dep = ARTIFACT_REPO.get(a.group(1)) if a else None
            if dep and dep != repo and dep not in allowed:
                out.append((f"F1/salasim_gmpls_{repo}", f"{rel(pom)}: {repo} depends on {dep} ({a.group(1)})"))
    return out


# ---------------------------------------------------------------- F2 Java package rules (crude, until ArchUnit)

def check_f2() -> list[Violation]:
    """Core and device code outside a simulation package must not use one (pce/emulator 'sim' packages), whether by
    import or by a fully qualified name; one count per import line or per qualified reference."""
    out: list[Violation] = []
    sim_ref = re.compile(r"\b((?:[a-z]\w*\.)+sim\.[\w.*]+)")
    for repo in CORE_JAVA:
        for p in java_sources(repo):
            if "sim" in p.parts:
                continue
            for lineno, line in enumerate(strip_java_comments(p.read_text(errors="replace")).splitlines(), 1):
                if line.lstrip().startswith("package "):
                    continue
                for m in sim_ref.finditer(line):
                    out.append((f"F2/{repo}", f"{rel(p)}:{lineno}: uses {m.group(1)}"))
    return out


# ---------------------------------------------------------------- F3 Python package boundaries

# importer -> packages it must not import (system-architecture v2 section 4: three top-level packages)
F3_FORBIDDEN = {"salasim_core_history": ["salasim", "salasim_sim"], "salasim": ["salasim_sim"]}


def check_f3() -> list[Violation]:
    out: list[Violation] = []
    root = WORKSPACE / BACKEND_PKG_ROOT
    for pkg, banned in F3_FORBIDDEN.items():
        if not (root / pkg).is_dir():
            continue  # package not created yet (P3/P6); the rule applies from its first file
        pat = re.compile(r"^\s*(?:from|import)\s+(" + "|".join(map(re.escape, banned)) + r")(?:\.|\s|$)")
        for p in sorted((root / pkg).rglob("*.py")):
            for lineno, line in enumerate(p.read_text(errors="replace").splitlines(), 1):
                m = pat.match(line)
                if m:
                    out.append((f"F3/{pkg}", f"{rel(p)}:{lineno}: imports {m.group(1)}"))
    return out


# ---------------------------------------------------------------- F7 time only through an injected Clock

F7_CALLS = re.compile(r"\b(System\.currentTimeMillis\(\)|Instant\.now\(\)|(?:LocalDateTime|OffsetDateTime|"
                      r"ZonedDateTime|LocalDate|LocalTime)\.now\(\)|new\s+Date\(\))")
F7_ALLOWED_FILES = set()  # the clock adapters, once they exist (P1)


def check_f7() -> list[Violation]:
    out: list[Violation] = []
    for repo in CORE_JAVA:
        for p in java_sources(repo):
            if p.name in F7_ALLOWED_FILES:
                continue
            for lineno, line in enumerate(strip_java_comments(p.read_text(errors="replace")).splitlines(), 1):
                for m in F7_CALLS.finditer(line):
                    out.append((f"F7/{repo}", f"{rel(p)}:{lineno}: {m.group(1)}"))
    return out


# ---------------------------------------------------------------- F8 no static mutable state

F8_STATIC_NONFINAL = re.compile(r"^\s*(?:(?:public|protected|private|volatile|transient)\s+)*static\s+"
                                r"(?:(?:volatile|transient)\s+)*(?!final\b|class\b|interface\b|enum\b|record\b|"
                                r"abstract\b|synchronized\b|native\b|void\b|<)[\w.<>\[\], ?]+\s+\w+\s*(?:=|;)")
F8_STATIC_MUTABLE_FINAL = re.compile(r"\bstatic\s+final\s+[\w.<>\[\], ?]+\s+\w+\s*=\s*new\s+(?:java\.util\.)?"
                                     r"(?:concurrent\.)?(HashMap|LinkedHashMap|TreeMap|ArrayList|LinkedList|HashSet|"
                                     r"LinkedHashSet|TreeSet|ConcurrentHashMap|ConcurrentLinkedQueue|"
                                     r"CopyOnWriteArrayList|ArrayDeque|EnumMap|AtomicInteger|AtomicLong|"
                                     r"AtomicBoolean|AtomicReference)\b")


def check_f8() -> list[Violation]:
    out: list[Violation] = []
    for repo in CORE_JAVA:
        for p in java_sources(repo):
            for lineno, line in enumerate(strip_java_comments(p.read_text(errors="replace")).splitlines(), 1):
                if F8_STATIC_NONFINAL.match(line) and "(" not in line.split("=", 1)[0]:
                    out.append((f"F8/{repo}", f"{rel(p)}:{lineno}: static non-final field"))
                elif m := F8_STATIC_MUTABLE_FINAL.search(line):
                    out.append((f"F8/{repo}", f"{rel(p)}:{lineno}: static final {m.group(1)}"))
    return out


# ---------------------------------------------------------------- F9 PCEP code points

PCEP_CONSTANTS = "salasim_gmpls_protocols/es/tid/pce/pcep/objects/ObjectParameters.java"
PCEP_REGISTRY = "salasim_gmpls_pce/docs/pcep-codepoints.md"
# simulation code points (docs/simulation-awareness-inventory.md section 4.5, REMOVE + NEUTRALIZE)
F9_SIM_TLVS = {65504, 65510, 65511, 65514, 65519}
F9_SIM_NOTIFICATION_TYPES = {32, 33, 34}
F9_FAMILIES = ("PCEP_TLV_", "PCEP_NOTIFICATION_TYPE_")


def check_f9() -> list[Violation]:
    out: list[Violation] = []
    key = "F9/salasim_gmpls_protocols"
    src = WORKSPACE / PCEP_CONSTANTS
    registry = (WORKSPACE / PCEP_REGISTRY).read_text()
    seen: dict[tuple[str, int], str] = {}
    const = re.compile(r"static\s+final\s+int\s+([A-Z0-9_]+)\s*=\s*(0x[0-9A-Fa-f]+|\d+)\s*;")
    for lineno, line in enumerate(strip_java_comments(src.read_text()).splitlines(), 1):
        m = const.search(line)
        if not m:
            continue
        name, value = m.group(1), int(m.group(2), 0)
        fam = next((f for f in F9_FAMILIES if name.startswith(f)), None)
        if fam is None:
            continue
        where = f"{rel(src)}:{lineno}: {name}={value}"
        if (fam, value) in seen:
            out.append((key, f"{where}: duplicate of {seen[fam, value]}"))
        seen.setdefault((fam, value), name)
        # the registry names a constant in full or by its suffix (`LABEL_REQUEST`, `EXPERIMENTAL_SALASIM_FRAME`)
        short = re.sub(r"^(PCEP_TLV_TYPE_|PCEP_TLV_|PCEP_NOTIFICATION_TYPE_)", "", name)
        if f"`{name}`" not in registry and f"`{short}`" not in registry:
            out.append((key, f"{where}: not in {PCEP_REGISTRY}"))
        sim = value in (F9_SIM_TLVS if fam == "PCEP_TLV_" else F9_SIM_NOTIFICATION_TYPES) or f4_hit(name)
        if sim:
            out.append((key, f"{where}: simulation code point"))
    return out


# ---------------------------------------------------------------- F6 / F10: subjects arrive in P3

def check_f6() -> list[Violation]:
    return []  # core DDL + core_api_v1 views are written in P3; this becomes the catalogue + SQL scan then


def check_f10() -> list[Violation]:
    return []  # fact emitters move to salasim-fact in P3; this becomes the payload contract test then


CHECKS = {"F1": check_f1, "F2": check_f2, "F3": check_f3, "F4": check_f4, "F5": check_f5, "F6": check_f6,
          "F7": check_f7, "F8": check_f8, "F9": check_f9, "F10": check_f10}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", metavar="CHECK", help="print the violations of one check (F1..F10)")
    ap.add_argument("--write-baseline", action="store_true")
    ap.add_argument("--allow-increase", action="store_true")
    args = ap.parse_args()

    if args.list:
        for _, detail in CHECKS[args.list.upper()]():
            print(detail)
        return 0

    counts: Counter[str] = Counter()
    for name, fn in CHECKS.items():
        counts[name + "/total"] += 0
        for key, _ in fn():
            counts[key] += 1
    baseline: dict[str, int] = json.loads(BASELINE.read_text())["counts"] if BASELINE.exists() else {}

    keys = sorted(set(counts) | set(baseline), key=lambda k: (int(k.split("/")[0][1:]), k))
    worse, better = [], []
    by_check = defaultdict(list)
    for k in keys:
        if k.endswith("/total"):
            continue
        cur, base = counts.get(k, 0), baseline.get(k, 0)
        by_check[k.split("/")[0]].append((k, cur, base))
        (worse if cur > base else better if cur < base else []).append((k, cur, base))

    for check in CHECKS:
        rows = by_check.get(check, [])
        cur, base = sum(r[1] for r in rows), sum(r[2] for r in rows)
        print(f"{check:<4} {cur:>6}  (baseline {base})")
        for k, c, b in rows:
            mark = "  INCREASED" if c > b else "  lower: update baseline" if c < b else ""
            print(f"       {k.split('/', 1)[1]:<34} {c:>6} / {b}{mark}")

    if args.write_baseline:
        if worse and not args.allow_increase:
            print("refusing to raise the baseline (use --allow-increase and explain it in review):", file=sys.stderr)
            for k, c, b in worse:
                print(f"  {k}: {b} -> {c}", file=sys.stderr)
            return 1
        data = {"comment": "Ratchet baselines for docs/tools/fitness/fitness.py; counts may only go down.",
                "counts": {k: counts.get(k, 0) for k in keys if not k.endswith("/total") and counts.get(k, 0)}}
        BASELINE.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
        print(f"baseline written: {rel(BASELINE)}")
        return 0

    if worse:
        print("\nFITNESS: FAIL, counts above baseline:")
        for k, c, b in worse:
            print(f"  {k}: {c} > {b}   (fitness.py --list {k.split('/')[0]})")
        return 1
    print("\nFITNESS: ok" + (f" ({len(better)} keys below baseline; lower it with --write-baseline)" if better else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
