"""AI生成；Java 7 AST方法发现与词法行数统计。离线依赖见vendor。"""
from pathlib import Path
import argparse, collections, csv, hashlib, json, sys
sys.path.insert(0, str(Path(__file__).parent / 'vendor'))
import javalang

DECL = (javalang.tree.MethodDeclaration, javalang.tree.ConstructorDeclaration)
TYPES = (javalang.tree.ClassDeclaration, javalang.tree.InterfaceDeclaration,
         javalang.tree.EnumDeclaration, javalang.tree.AnnotationDeclaration)

def tokens(source):
    return list(javalang.tokenizer.tokenize(source))

def token_signature(source):
    return [(type(t).__name__, t.value) for t in tokens(source)]

def occupied(ts):
    out = set()
    for t in ts:
        out.update(range(t.position.line, t.position.line + t.value.count('\n') + 1))
    return out

def declarations(source, filename):
    ts = tokens(source)
    tree = javalang.parse.parse(source)
    rows = []
    for chain, node in tree:
        if not isinstance(node, DECL) or node.body is None:
            continue
        start = next(i for i,t in enumerate(ts) if tuple(t.position) >= tuple(node.position))
        name_i = next(i for i in range(start,len(ts)-1)
                      if ts[i].value == node.name and ts[i+1].value == '(')
        depth = 0
        close = None
        for i in range(name_i+1,len(ts)):
            depth += (ts[i].value == '(') - (ts[i].value == ')')
            if depth == 0:
                close = i
                break
        opening = next(i for i in range(close+1,len(ts)) if ts[i].value == '{')
        depth = 0
        for end in range(opening,len(ts)):
            depth += (ts[end].value == '{') - (ts[end].value == '}')
            if depth == 0:
                break
        span = ts[start:end+1]
        classes = [n.name for n in chain if isinstance(n,TYPES)]
        outer = [n.name for n in chain if isinstance(n,DECL)]
        scope = '.'.join(classes) + (('/anonymous@'+'.'.join(outer)) if outer else '')
        args = ','.join(p.type.name + ('[]'*len(p.type.dimensions or []))
                        + ('...' if p.varargs else '') for p in node.parameters)
        ops = collections.Counter(t.value for t in span if isinstance(t,
            (javalang.tokenizer.Operator, javalang.tokenizer.Separator,
             javalang.tokenizer.Keyword, javalang.tokenizer.BasicType,
             javalang.tokenizer.Modifier)))
        rows.append(dict(file=filename, owner=scope, method=node.name,
            signature=node.name+'('+args+')', kind='constructor' if isinstance(node,javalang.tree.ConstructorDeclaration) else 'method',
            start=node.position.line,end=ts[end].position.line,
            sloc=len(occupied(span)),physical_lines=ts[end].position.line-node.position.line+1,
            operator_kinds=len(ops),operators=dict(sorted(ops.items()))))
    return rows, tree

def csv_write(path, rows, fields):
    with path.open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields,extrasaction='ignore'); w.writeheader(); w.writerows(rows)

def analyze(root,out):
    out.mkdir(parents=True,exist_ok=True)
    rows=[]; files=[]; classes=0; hashes={}
    for f in sorted((root/'src').rglob('*.java')):
        source=f.read_text(encoding='utf-8'); rel=f.relative_to(root).as_posix()
        found,tree=declarations(source,rel); rows.extend(found)
        classes+=sum(isinstance(n,TYPES) for _,n in tree)
        files.append(dict(file=rel,physical_lines=len(source.splitlines()),sloc=len(occupied(tokens(source)))))
        hashes[rel]=hashlib.sha256(f.read_bytes()).hexdigest()
    methods=[r for r in rows if r['kind']=='method']
    avg=sum(r['sloc'] for r in methods)/len(methods)
    for r in rows:r['above_mean']=r['kind']=='method' and r['sloc']>avg
    candidates=sorted([r for r in methods if r['above_mean']],key=lambda r:(-r['sloc'],r['file'],r['start']))
    summary=dict(java_files=len(files),named_types=classes,methods_with_body=len(methods),
        constructors=len(rows)-len(methods),mean_method_sloc=avg,
        file_sloc=sum(r['sloc'] for r in files),physical_lines=sum(r['physical_lines'] for r in files),
        mean_scope='所有有实现的方法，含内部类和匿名类方法；不含构造器及接口无实现声明',
        nested_scope='父方法范围包含匿名类声明及其方法，因此父子方法范围可能重叠，不将方法SLOC求和冒充项目SLOC',
        sloc_scope='从声明所在行至配对结束花括号；仅计算至少有Java词法token的物理行，含声明及独立花括号行',
        operator_scope='扩展词法口径：Operator、Separator、Keyword、BasicType、Modifier的不同token值；不计标识符和字面量，不等同于完整Halstead指标',
        errors=[])
    fields=['file','owner','signature','kind','start','end','physical_lines','sloc','operator_kinds','above_mean']
    csv_write(out/'all_methods.csv',rows,fields)
    csv_write(out/'above_mean_candidates.csv',candidates,fields)
    csv_write(out/'files.csv',files,['file','physical_lines','sloc'])
    for name,obj in [('summary.json',summary),('methods_detail.json',rows),('source_sha256.json',hashes)]:
        (out/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False,indent=2))
    return rows,summary

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    a=p.parse_args();analyze(a.root,a.out)
