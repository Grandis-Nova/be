"""CI가 잘못된 마이그레이션을 허용하지 않는지 임시 Git 저장소에서 검사한다."""
import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import migrations


class MigrationChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / migrations.PREFIX
        self.directory.mkdir(parents=True)
        self.original = self.directory / "V202609221000__init_shop.sql"
        self.original.write_text("CREATE TABLE customers (id BIGINT PRIMARY KEY);\n")
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-qm", "initial")
        for attribute, value in [("ROOT", self.root), ("DIRECTORY", self.directory)]:
            context = patch.object(migrations, attribute, value)
            context.start()
            self.addCleanup(context.stop)

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def check(self):
        with contextlib.redirect_stdout(io.StringIO()):
            migrations.check("HEAD")

    def test_newer_version_is_allowed(self):
        (self.directory / "V202609221001__add_email.sql").write_text(
            "ALTER TABLE customers ADD email VARCHAR(255);\n")
        self.check()

    def test_duplicate_version_is_rejected(self):
        (self.directory / "V202609221000__duplicate.sql").write_text("SELECT 1;\n")
        with self.assertRaisesRegex(ValueError, "중복 버전"):
            self.check()

    def test_edited_migration_is_rejected(self):
        self.original.write_text("SELECT 1;\n")
        with self.assertRaisesRegex(ValueError, "수정/삭제/이름 변경 금지"):
            self.check()

    def test_deleted_migration_is_rejected(self):
        self.original.unlink()
        (self.directory / "V202609221001__replacement.sql").write_text("SELECT 1;\n")
        with self.assertRaisesRegex(ValueError, "수정/삭제/이름 변경 금지"):
            self.check()

    def test_late_merged_older_version_is_rejected(self):
        (self.directory / "V202609220959__late.sql").write_text("SELECT 1;\n")
        with self.assertRaisesRegex(ValueError, "최신 버전"):
            self.check()

    def test_invalid_names_are_rejected(self):
        for name in ["V1__init.sql", "V20260922100001__seconds.sql", "V202602301000__invalid_date.sql"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                migrations.version(name)


if __name__ == "__main__":
    unittest.main()
