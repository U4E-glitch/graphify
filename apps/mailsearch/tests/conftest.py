import sys
from pathlib import Path

# The app is a plain package in the parent directory; no install needed to test.
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
