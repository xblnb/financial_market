"""Diff the Java probe output against the numpy reference (implementation check)."""

from __future__ import annotations

import os
import sys

import numpy as np

PAIRS = [
    ("tok_s1", "ref_tok_s1.txt", "java_tok_s1.txt", True),
    ("tok_s2", "ref_tok_s2.txt", "java_tok_s2.txt", True),
    ("tok_decoded", "ref_tok_decoded.txt", "java_tok_decoded.txt", False),
    ("s1_logits", "ref_s1_logits.txt", "java_s1_logits.txt", False),
    ("s2_logits", "ref_s2_logits.txt", "java_s2_logits.txt", False),
]


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    ref_dir, java_dir = sys.argv[1], sys.argv[2]
    worst = 0.0
    ok = True
    for name, ref_file, java_file, is_int in PAIRS:
        ref = np.loadtxt(os.path.join(ref_dir, ref_file))
        java = np.loadtxt(os.path.join(java_dir, java_file))
        if ref.shape != java.shape:
            print(f"[FAIL] {name}: shape {ref.shape} vs {java.shape}")
            ok = False
            continue
        if is_int:
            diff = int(np.max(np.abs(ref.astype(np.int64) - java.astype(np.int64))))
            print(f"[{'PASS' if diff == 0 else 'FAIL'}] {name}: exact match, max|diff|={diff}, n={ref.size}")
            ok &= diff == 0
        else:
            ref = ref.astype(np.float64)
            java = java.astype(np.float64)
            abs_diff = np.max(np.abs(ref - java))
            denom = np.maximum(np.abs(ref), 1e-8)
            rel_diff = np.max(np.abs(ref - java) / denom)
            worst = max(worst, abs_diff)
            limit = 2e-4
            print(f"[{'PASS' if abs_diff <= limit else 'FAIL'}] {name}: max|diff|={abs_diff:.3e} "
                  f"max rel={rel_diff:.3e}, mean|v|={np.abs(ref).mean():.4f}, n={ref.size}")
            ok &= abs_diff <= limit
    print(f"overall worst absolute difference: {worst:.3e}")
    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
