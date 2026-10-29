"""Build fresh sources and exercise indexing in disposable databases."""
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from build import build


def check_performance(classpath, directory):
    directory = Path(directory)
    for entry, name, modes in [
        ("PerformanceTests", "db", ["prepare", "verify"]),
        ("IndexMigrationTests", "migration", ["prepare", "reader", "upgrade", "verify"]),
    ]:
        for mode in modes:
            subprocess.run(["java", "-cp", classpath, entry, str(directory / name), mode],
                           cwd=ROOT, check=True, timeout=90)


def database_hashes():
    directory = ROOT / "dbms" / "db"
    return {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in directory.glob("*") if path.is_file()}


def main():
    before = database_hashes()
    try:
        with tempfile.TemporaryDirectory(prefix="sql-performance-") as temporary:
            directory = Path(temporary)
            classpath = build(directory / "classes", tests=True)
            check_performance(classpath, directory)
    finally:
        if database_hashes() != before:
            raise AssertionError("Existing dbms/db files changed during verification")


if __name__ == "__main__":
    main()
