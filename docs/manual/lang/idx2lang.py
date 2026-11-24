"""Convert a translation keyed by string number (from strings_en.json) into lang/<xx>.json keyed by English.

Usage: python idx2lang.py xx_idx.json xx      (run make_manual.py extract first)
Strings that are not translated stay English (names shown in the app are kept on purpose).
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
en = json.load(open(HERE / 'strings_en.json', encoding='utf-8'))
idx = json.load(open(sys.argv[1], encoding='utf-8'))
out = {}
bad = 0
for k, v in idx.items():
    e = en[int(k)]
    for tag in ('<b>', '</b>', '&amp;', '→'):
        if e.count(tag) != v.count(tag):
            print('check %s %s: %r' % (k, tag, e[:60]))
            bad += 1
    if re.search(r'<(?!/?b>)', v):
        print('tag? %s' % k)
    out[e] = v
json.dump(out, open(HERE / (sys.argv[2] + '.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(sys.argv[2], len(out), 'strings,', bad, 'warnings')
