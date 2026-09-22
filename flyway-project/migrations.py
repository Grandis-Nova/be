#!/usr/bin/env python3
"""KST 분 단위 마이그레이션 생성 및 PR의 버전/불변성 검사."""
import argparse
from datetime import datetime
from pathlib import Path
import re
import subprocess
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent
DIRECTORY = ROOT / "flyway-project/migrations"
PREFIX = "flyway-project/migrations/"
PATTERN = re.compile(r"V(\d{12})__([a-z][a-z0-9_]*)\.sql")


def version(name):
    match = PATTERN.fullmatch(name)
    if not match:
        raise ValueError(f"파일명은 VyyyyMMddHHmm__snake_case.sql 형식이어야 합니다: {name}")
    datetime.strptime(match[1], "%Y%m%d%H%M")
    return match[1]


def check(base):
    files = sorted(DIRECTORY.glob("*.sql"))
    if not files:
        raise ValueError("마이그레이션 파일이 없습니다.")
    versions = [version(path.name) for path in files]
    if len(versions) != len(set(versions)):
        raise ValueError("중복 버전이 있습니다. 미적용 파일에 새 KST 분 단위 버전을 부여하세요.")
    if base:
        def git(*args):
            return subprocess.check_output(["git", *args], cwd=ROOT)

        previous = git("ls-tree", "-r", "--name-only", base, "--", PREFIX).decode().splitlines()
        previous = [name for name in previous if name.endswith(".sql")]
        for name in previous:
            path = ROOT / name
            if not path.exists() or path.read_bytes() != git("show", f"{base}:{name}"):
                raise ValueError(f"기준 브랜치의 마이그레이션 수정/삭제/이름 변경 금지: {name}")
        latest = max((version(Path(name).name) for name in previous), default="")
        for path, number in zip(files, versions):
            if PREFIX + path.name not in previous and number <= latest:
                raise ValueError(f"새 버전은 기준 브랜치 최신 버전 {latest}보다 커야 합니다: {path.name}")
    print(f"마이그레이션 {len(files)}개 검사 통과")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check").add_argument("--base", help="불변성과 신규 버전 순서를 비교할 Git ref")
    commands.add_parser("new").add_argument("description", help="예: add_customer_column")
    args = parser.parse_args()
    try:
        if args.command == "check":
            check(args.base)
            return
        if not re.fullmatch(r"[a-z][a-z0-9_]*", args.description):
            raise ValueError("설명은 영문 소문자 snake_case로 입력하세요.")
        check(None)
        number = datetime.now(ZoneInfo("Asia/Seoul")).strftime("%Y%m%d%H%M")
        latest = max(version(path.name) for path in DIRECTORY.glob("*.sql"))
        if number <= latest:
            raise ValueError(f"현재 분의 버전이 이미 있거나 최신 버전({latest})보다 이전입니다. 다음 분에 생성하세요.")
        path = DIRECTORY / f"V{number}__{args.description}.sql"
        with path.open("x") as stream:
            stream.write(f"-- {args.description}\n")
        print(path.relative_to(ROOT))
    except ValueError as error:
        parser.exit(1, f"오류: {error}\n")


if __name__ == "__main__":
    main()
