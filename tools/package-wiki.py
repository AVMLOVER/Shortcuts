#!/usr/bin/env python3
"""Export repository Markdown to a self-contained GitHub Wiki upload bundle."""
import argparse
from pathlib import Path
import re
import zipfile


def main():
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=repo / 'dist/wiki-export')
    args = parser.parse_args()
    source = repo / 'docs/wiki'
    output = args.output_dir.resolve()
    if output == repo or output == source or source in output.parents or output in source.parents:
        parser.error('Output must be separate from the source directory and its ancestors.')
    pages = sorted(source.glob('*.md'))
    if not pages or not (source / 'Home.md').is_file():
        parser.error('docs/wiki/Home.md and the other Wiki pages are required.')
    known = {page.name for page in pages}
    link = re.compile(r'(?<!!)\[([^\]\n]+)\]\(([^)\s]+)\)')

    def transform(match):
        label, target = match.groups()
        path, separator, fragment = target.partition('#')
        if path in known:
            target = Path(path).stem + (separator + fragment if separator else '')
        elif not (target.startswith('#') or re.match(r'[a-zA-Z][a-zA-Z0-9+.-]*:', target)):
            raise ValueError('Unknown or nonportable Wiki link: ' + target)
        return f'[{label}]({target})'

    # Validate and transform everything before writing any output.
    converted = {page.name: link.sub(transform, page.read_text(encoding='utf-8')) for page in pages}
    guide = (repo / 'docs/WIKI_UPLOAD.md').read_text(encoding='utf-8')
    output.mkdir(parents=True, exist_ok=True)
    destination = output / 'wiki'
    destination.mkdir(exist_ok=True)
    for name, content in converted.items():
        (destination / name).write_text(content, encoding='utf-8', newline='\n')
    (output / '上传说明.md').write_text(guide, encoding='utf-8', newline='\n')
    archive = output.with_name(output.name + '.zip')
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
        for name in sorted(converted):
            bundle.write(destination / name, 'wiki/' + name)
        bundle.write(output / '上传说明.md', '上传说明.md')
    with zipfile.ZipFile(archive) as bundle:
        if bundle.testzip() is not None:
            raise RuntimeError('Wiki archive integrity check failed.')
    print(f'Exported {len(pages)} Markdown files to {destination}')
    print(f'Upload bundle: {archive}')


if __name__ == '__main__':
    main()
