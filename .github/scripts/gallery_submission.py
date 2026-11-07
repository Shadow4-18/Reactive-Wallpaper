#!/usr/bin/env python3
"""Checks a gallery suggestion (a GitHub issue) and, when approved, adds it to gallery/looks.json.

  gallery_submission.py check  -> writes a reply for the issue to reply.md; exit 0 if the look is valid
  gallery_submission.py add    -> adds the look to gallery/looks.json; exit 0 if it was added

Reads the issue from the ISSUE_TITLE, ISSUE_BODY and ISSUE_AUTHOR environment variables.
A look code is decoded exactly as the app does (see LookCode.java): base64url, raw deflate, JSON.
"""
import base64
import json
import os
import re
import sys
import zlib

LOOKS = 'gallery/looks.json'
CODE = re.compile(r'(?:resona://look/|/look/#)([A-Za-z0-9_-]{8,8000})')
MAX_LOOKS = 200


def decode(data):
    raw = base64.urlsafe_b64decode(data + '=' * (-len(data) % 4))
    text = zlib.decompressobj(-15).decompress(raw, 64 * 1024)
    look = json.loads(text)
    if look.get('v') != 1 or not isinstance(look.get('s'), dict) or not look['s']:
        raise ValueError('not a version 1 look with settings')
    return look


def field(body, label):
    # [ \t] rather than \s, so an empty field doesn't swallow the next line.
    m = re.search(r'^[ \t]*' + label + r'[ \t]*:[ \t]*(\S.*)$', body, re.MULTILINE | re.IGNORECASE)
    return m.group(1).strip() if m else ''


def clean(text, limit):
    text = re.sub(r'[\x00-\x1f<>]', '', text).strip()
    return text[:limit]


def parse():
    body = os.environ.get('ISSUE_BODY') or ''
    m = CODE.search(body)
    if not m:
        return None, 'I couldn\'t find a look code in this issue. In Resona, use Looks > Browse the look gallery > Suggest yours, which fills it in.'
    data = m.group(1)
    try:
        look = decode(data)
    except Exception:
        return None, 'The look code here looks cut off or damaged. Please suggest it again from Resona.'
    name = clean(field(body, 'Name') or look.get('n') or '', 60)
    title = os.environ.get('ISSUE_TITLE') or ''
    if not name and ':' in title:
        name = clean(title.split(':', 1)[1], 60)
    if not name or name.lower() == 'my look':
        name = name or 'Shared look'
    entry = {
        'name': name,
        'by': clean(os.environ.get('ISSUE_AUTHOR') or '', 40),
        'about': clean(field(body, 'What it looks like'), 200),
        'code': 'resona://look/' + data,
    }
    return entry, None


def main():
    mode = sys.argv[1]
    entry, problem = parse()
    if mode == 'check':
        with open('reply.md', 'w') as f:
            if problem:
                f.write(problem + '\n')
            else:
                f.write('Thanks! This look (“%s”) is valid. Once it\'s approved it will appear in '
                        'everyone\'s gallery.\n' % entry['name'])
        sys.exit(1 if problem else 0)
    if problem:
        print(problem)
        sys.exit(1)
    with open(LOOKS) as f:
        gallery = json.load(f)
    looks = gallery['looks']
    if any(l.get('code') == entry['code'] for l in looks):
        print('already in the gallery')
        sys.exit(2)
    if len(looks) >= MAX_LOOKS:
        print('gallery is full')
        sys.exit(1)
    looks.append(entry)
    with open(LOOKS, 'w') as f:
        json.dump(gallery, f, indent=2, ensure_ascii=False)
        f.write('\n')
    print('added', entry['name'])


if __name__ == '__main__':
    main()
