"""验证实际协作仓库，而不是仅验证随包附带的源码副本。"""
from pathlib import Path
import contextlib
import difflib
import io
import json
import subprocess
import tempfile
from measure import analyze, token_signature

PACKAGE = Path(__file__).resolve().parents[1]
REPOSITORY = PACKAGE.parents[1]
BASE = 'b94cc3908dbdc5784972478431f16c7d6904905a'
EXPECTED = {
    'model/Note.java': 49,
    'model/WorkingNote.java': 73,
    'tool/BackupUtils.java': 46,
    'tool/DataUtils.java': 44,
    'tool/GTaskStringUtils.java': 49,
    'tool/ResourceParser.java': 37,
}


def git(*args):
    return subprocess.check_output(['git', *args], cwd=REPOSITORY)


def run():
    base_files = git('ls-tree', '-r', '--name-only', BASE, 'src').decode().splitlines()
    java_files = [name for name in base_files if name.endswith('.java')]
    actual_files = sorted(p.relative_to(REPOSITORY).as_posix()
                          for p in (REPOSITORY / 'src').rglob('*.java'))
    assert sorted(java_files) == actual_files, 'Java文件清单发生变化'
    changed = {}
    with tempfile.TemporaryDirectory() as directory:
        baseline = Path(directory) / 'baseline'
        for name in java_files:
            original = git('show', BASE + ':' + name).decode('utf-8')
            current = (REPOSITORY / name).read_text('utf-8')
            assert token_signature(original) == token_signature(current), name
            target = baseline / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(original, encoding='utf-8')
            before, after = original.splitlines(), current.splitlines()
            if before == after:
                continue
            additions = []
            for operation, i1, i2, j1, j2 in difflib.SequenceMatcher(
                    None, before, after, autojunk=False).get_opcodes():
                if operation == 'equal':
                    continue
                assert operation == 'insert', (name, operation)
                additions.extend(after[j1:j2])
            assert all(line.strip().startswith('//') and
                       any('\u4e00' <= c <= '\u9fff' for c in line)
                       for line in additions), name
            changed[name.removeprefix('src/net/micode/notes/')] = len(additions)
            copy = PACKAGE / 'source/annotated' / name
            assert current == copy.read_text('utf-8'), '注释源码副本未同步：' + name
        assert changed == EXPECTED, changed
        with contextlib.redirect_stdout(io.StringIO()):
            old_rows, old_summary = analyze(baseline, Path(directory) / 'old_metrics')
            new_rows, new_summary = analyze(REPOSITORY, Path(directory) / 'new_metrics')
        def comparable(rows):
            return sorted((r['file'], r['owner'], r['signature'], r['kind'],
                           r['sloc'], r['operator_kinds']) for r in rows)
        assert comparable(old_rows) == comparable(new_rows), '方法度量发生变化'
        for key in ('file_sloc', 'methods_with_body', 'constructors', 'mean_method_sloc'):
            assert old_summary[key] == new_summary[key], key
        result = dict(
            baseline=BASE,
            java_files=len(java_files),
            changed_files=changed,
            added_chinese_comment_lines=sum(changed.values()),
            java_tokens_equal=True,
            method_metrics_equal=True,
            methods_with_body=new_summary['methods_with_body'],
            constructors=new_summary['constructors'],
            mean_method_sloc=new_summary['mean_method_sloc'],
            android_compile_and_runtime='未执行',
        )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    run()
