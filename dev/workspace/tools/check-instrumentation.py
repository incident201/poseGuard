#!/usr/bin/env python3
"""ADB may return zero even when AndroidJUnitRunner reports test failures."""
import pathlib
import re
import sys

log = pathlib.Path(sys.argv[1])
text = log.read_text()
success = re.search(r"OK \((\d+) tests?\)", text)
if not success or int(success.group(1)) == 0 or re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=", text):
    raise SystemExit(f"Instrumentation failed: {log}")
print("PASS:", success.group(1), "tests")
