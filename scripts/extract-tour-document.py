#!/usr/bin/env python3
"""Bounded local PDF text extraction; no networking or document instructions executed."""
import json, sys
from pathlib import Path

def extract(path):
    import resource
    resource.setrlimit(resource.RLIMIT_CPU, (20, 20))
    resource.setrlimit(resource.RLIMIT_FSIZE, (1_000_000, 1_000_000))
    from pypdf import PdfReader
    reader = PdfReader(path, strict=True)
    if reader.is_encrypted:
        raise ValueError('Verschlüsselte PDF wird nicht unterstützt')
    if len(reader.pages) > 100:
        raise ValueError('PDF überschreitet 100 Seiten')
    pages, size = [], 0
    for i, page in enumerate(reader.pages):
        text = page.extract_text() or ''
        size += len(text)
        if size > 200_000:
            raise ValueError('PDF-Text überschreitet 200.000 Zeichen')
        pages.append({'page': i + 1, 'text': text})
    if not any(p['text'].strip() for p in pages):
        raise ValueError('Kein PDF-Text gefunden; Scan benötigt OCR. Bitte OCR-Text oder durchsuchbare PDF bereitstellen')
    return {'pages': pages}

if __name__ == '__main__':
    try:
        print(json.dumps(extract(Path(sys.argv[1])), ensure_ascii=False))
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
