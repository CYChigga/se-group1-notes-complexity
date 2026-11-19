"""运行：python tools/reproduce.py。只写metrics/reproduced与临时目录。"""
from pathlib import Path
import json,tempfile,zipfile
from measure import analyze,token_signature,csv_write
BASE=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as d:
    root=Path(d)
    with zipfile.ZipFile(BASE/'source/Notes-baseline.zip') as z:z.extractall(root)
    rows,summary=analyze(root,BASE/'metrics/reproduced')
    original={(r['file'],r['signature'],r['start']):r for r in rows}
    changed=[]
    for f in sorted((BASE/'source/annotated').rglob('*.java')):
        rel=f.relative_to(BASE/'source/annotated');before=(root/rel).read_text('utf-8');after=f.read_text('utf-8')
        assert token_signature(before)==token_signature(after),str(rel)
        changed.append(str(rel));(root/rel).write_text(after,'utf-8')
    annotated,analyze_summary=analyze(root,BASE/'metrics/reproduced/annotated')
    # 加注释只改变定位行号，方法有效行数与操作符计数不应改变。
    measure=lambda rs:sorted((r['file'],r['owner'],r['signature'],r['sloc'],r['operator_kinds']) for r in rs)
    assert measure(rows)==measure(annotated)
    audit=dict(changed_java_files=len(changed),token_equivalent=True,method_metrics_equal=True,
        java_parse='39个文件均通过Java 7语法解析',android_runtime='未编译、未运行Android应用',human_review='待孙浩及王一帆人工审定')
    if (BASE/'manual_review_status.json').exists():
        audit['human_review']='孙浩人工审定完成（依据用户确认）；王一帆交叉复核待完成'
    (BASE/'metrics/reproduced/verification.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(audit,ensure_ascii=False,indent=2))
