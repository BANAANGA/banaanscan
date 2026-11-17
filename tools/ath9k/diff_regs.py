#!/usr/bin/env python3
"""Diffs two register logs (Linux's from decode_trace.py --regs, ours from ath9k-pc), ignoring read values and
WMI command lines: python3 tools/ath9k/diff_regs.py linux.log ours.log"""
import difflib
import sys


def norm(path):
    out = []
    for l in open(path):
        l = l.strip()
        if l.startswith("R "):
            out.append(l.split(" =")[0])
        elif l.startswith(("W ", "M ")):
            out.append(l)
    return out


a, b = norm(sys.argv[1]), norm(sys.argv[2])
sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
same = sum(t.size for t in sm.get_matching_blocks())
print("linux %d ops, ours %d ops, %d in common" % (len(a), len(b), same))
for tag, i1, i2, j1, j2 in sm.get_opcodes():
    if tag == "equal":
        continue
    print("@@ linux %d-%d, ours %d-%d: %s" % (i1 + 1, i2, j1 + 1, j2, tag))
    for l in a[i1:i2][:12]:
        print("  - " + l)
    if i2 - i1 > 12:
        print("  - ... %d more" % (i2 - i1 - 12))
    for l in b[j1:j2][:12]:
        print("  + " + l)
    if j2 - j1 > 12:
        print("  + ... %d more" % (j2 - j1 - 12))
