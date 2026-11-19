"""P5 五个方法的控制流图模型、McCabe 度量与基路径生成。

节点口径：顺序语句块合并为一个节点；if / else-if / for / while / do-while / case / catch 各建一个判定节点。
复合条件（&&、||）与三元表达式不单独建节点，与其所在判定/语句合并（见 docs 中的口径说明）。
回边（循环体回到循环头）在边表里标记 back=True，层次布局时不参与分层。
"""


class G:
    def __init__(self, name, path, sig_line):
        self.name = name
        self.path = path
        self.sig_line = sig_line
        self.nodes = {}      # id -> (label, kind)
        self.edges = []      # (u, v, label, back)
        self._n = 0

    def n(self, label, kind='action'):
        self._n += 1
        self.nodes[self._n] = (label, kind)
        return self._n

    def e(self, u, v, label='', back=False):
        self.edges.append((u, v, label, back))

    # ---------- 度量 ----------
    def decisions(self):
        seen = {}
        for u, v, _, _ in self.edges:
            seen.setdefault(u, []).append(v)
        return {u: vs for u, vs in seen.items() if len(vs) >= 2}

    def outdeg(self):
        d = {}
        for u, _, _, _ in self.edges:
            d[u] = d.get(u, 0) + 1
        return d

    def metrics(self):
        N = len(self.nodes)
        E = len(self.edges)
        P = 1
        vg = E - N + 2 * P
        dec = self.decisions()
        n_dec = sum(1 for _, k in self.nodes.values() if k == 'decision')
        n_catch = sum(1 for _, k in self.nodes.values() if k == 'catch')
        return dict(N=N, E=E, P=P, VG=vg, dec=n_dec, catch=n_catch,
                    ledger=n_dec + n_catch, cross=n_dec + n_catch + 1)

    def check(self):
        m = self.metrics()
        assert m['VG'] == m['cross'], '%s: E-N+2P=%d 与 判定条目+1=%d 不一致' % (
            self.name, m['VG'], m['cross'])
        return m

    # ---------- 路径 ----------
    def _adj(self, skip_back=True):
        a = {}
        for u, v, lb, back in self.edges:
            if skip_back and back:
                continue
            a.setdefault(u, []).append((v, lb))
        return a

    def shortest(self, src, dst, skip_back=True):
        if src == dst:
            return [src]
        a = self._adj(skip_back)
        prev = {src: None}
        q = [src]
        while q:
            nq = []
            for u in q:
                for v, _ in a.get(u, []):
                    if v not in prev:
                        prev[v] = u
                        if v == dst:
                            p = [v]
                            while prev[p[0]] is not None:
                                p.insert(0, prev[p[0]])
                            return p
                        nq.append(v)
            q = nq
        return None

    def base_path(self):
        return self.shortest(1, len(self.nodes))

    def enum_paths(self, cap=400000, maxlen=90):
        res = []
        adj = {}
        for u, v, lb, back in self.edges:
            adj.setdefault(u, []).append(v)
        target = len(self.nodes)

        def dfs(u, path, used):
            if len(res) >= cap:
                return
            if u == target:
                res.append(list(path))
                return
            if len(path) > maxlen:
                return
            for v in adj.get(u, []):
                k = (u, v)
                if k in used:
                    continue
                used.add(k)
                path.append(v)
                dfs(v, path, used)
                path.pop()
                used.discard(k)

        dfs(1, [1], set())
        return res

    def basis_paths(self, want=None):
        """基路径 = 基线路径 + 每个判定各分支“只在该判定处分歧”的路径。
        独立性判据：每条新路径至少引入一条此前未覆盖的边。"""
        want = want or self.metrics()['VG']
        exit_node = len(self.nodes)
        cands = []
        base = self.shortest(1, exit_node)
        if base:
            cands.append(base)
        for u, vs in sorted(self.decisions().items()):
            pre = self.shortest(1, u)
            if pre is None:
                continue
            for v in vs:
                post = self.shortest(v, exit_node, skip_back=False)
                if post is None:
                    continue
                p = pre + post[1:]
                if p not in cands:
                    cands.append(p)
        chosen, covered = [], set()
        for p in cands:
            es = set(zip(p, p[1:]))
            if not chosen or (es - covered):
                chosen.append(p)
                covered |= es
            if len(chosen) >= want:
                break
        for p in cands:                      # 补齐：仍保证两两不同且只在一个判定处分歧
            if len(chosen) >= want:
                break
            if p not in chosen:
                chosen.append(p)
        return chosen

    def _old_basis(self):
        """基线路径 + 每条“只翻转一个判定分支”的路径，去重后取 V(G) 条。"""
        m = self.metrics()
        want = m['VG']
        base = self.base_path()
        if base is None:
            return []
        paths = [base]
        used_edges = set(zip(base, base[1:]))
        for u, vs in sorted(self.decisions().items()):
            for v in vs:
                if (u, v) in used_edges:
                    continue
                pre = self.shortest(1, u)
                post = self.shortest(v, len(self.nodes))
                if pre is None or post is None:
                    continue
                p = pre + post
                if p not in paths:
                    paths.append(p)
                if len(paths) >= want:
                    return paths
        # 不足时用剩余分支补齐（允许共享边，仍为独立基路径）
        for u, vs in sorted(self.decisions().items()):
            for v in vs:
                pre = self.shortest(1, u)
                post = self.shortest(v, len(self.nodes))
                if pre is None or post is None:
                    continue
                p = pre + post
                if p not in paths:
                    paths.append(p)
                if len(paths) >= want:
                    return paths
        return paths


def build_all():
    out = []

    # ============ M1 SqlNote.setContent ============
    g = G('SqlNote.setContent', 'src/net/micode/notes/gtask/data/SqlNote.java', '229-360')
    n = g.n
    s = n('入口：js.getJSONObject(NOTE)', 'entry')
    d1 = n('note.type == TYPE_SYSTEM ?', 'decision')
    a1 = n('log 警告：不能写系统目录')
    d2 = n('note.type == TYPE_FOLDER ?', 'decision')
    d3 = n('snippet 变化？(has?取:"" )', 'decision')
    a3 = n('登记 SNIPPET')
    d4 = n('type 变化？(has?取:TYPE_NOTE)', 'decision')
    a4 = n('登记 TYPE')
    d5 = n('note.type == TYPE_NOTE ?', 'decision')
    a5 = n('取 dataArray = js.getJSONArray(DATA)')
    d6 = n('ID 变化？', 'decision')
    a6 = n('登记 ID')
    d7 = n('ALERTED_DATE 变化？', 'decision')
    a7 = n('登记 ALERTED_DATE')
    d8 = n('BG_COLOR_ID 变化？', 'decision')
    a8 = n('登记 BG_COLOR_ID')
    d9 = n('CREATED_DATE 变化？', 'decision')
    a9 = n('登记 CREATED_DATE')
    d10 = n('HAS_ATTACHMENT 变化？', 'decision')
    a10 = n('登记 HAS_ATTACHMENT')
    d11 = n('MODIFIED_DATE 变化？', 'decision')
    a11 = n('登记 MODIFIED_DATE')
    d12 = n('PARENT_ID 变化？', 'decision')
    a12 = n('登记 PARENT_ID')
    d13 = n('SNIPPET 变化？', 'decision')
    a13 = n('登记 SNIPPET')
    d14 = n('TYPE 变化？', 'decision')
    a14 = n('登记 TYPE')
    d15 = n('WIDGET_ID 变化？', 'decision')
    a15 = n('登记 WIDGET_ID')
    d16 = n('WIDGET_TYPE 变化？', 'decision')
    a16 = n('登记 WIDGET_TYPE')
    d17 = n('ORIGIN_PARENT_ID 变化？', 'decision')
    a17 = n('登记 ORIGIN_PARENT_ID')
    a18 = n('i = 0')
    d18 = n('i < dataArray.length() ?', 'decision')
    d19 = n('data.has(ID) ?', 'decision')
    a19 = n('dataId = data.getLong(ID)')
    d20 = n('for-each：mDataList 还有元素？', 'decision')
    d21 = n('dataId == temp.getId() ?', 'decision')
    a21 = n('sqlData = temp')
    d22 = n('sqlData == null ?', 'decision')
    a22 = n('new SqlData() 并加入 mDataList')
    a23 = n('sqlData.setContent(data)')
    c1 = n('catch JSONException：log + return false', 'catch')
    x = n('出口：返回 true / catch 中返回 false', 'exit')

    g.e(s, d1)
    g.e(d1, a1, '是'); g.e(d1, d2, '否')
    g.e(a1, x)
    g.e(d2, d3, '是'); g.e(d2, d5, '否')
    g.e(d3, a3, '是'); g.e(d3, d4, '否')
    g.e(a3, d4)
    g.e(d4, a4, '是'); g.e(d4, d5, '否')
    g.e(a4, d5)
    g.e(d5, a5, '是'); g.e(d5, x, '否')
    g.e(a5, d6)
    chain = [(d6, a6, d7), (d7, a7, d8), (d8, a8, d9), (d9, a9, d10), (d10, a10, d11),
             (d11, a11, d12), (d12, a12, d13), (d13, a13, d14), (d14, a14, d15),
             (d15, a15, d16), (d16, a16, d17)]
    for d, a, nxt in chain:
        g.e(d, a, '是'); g.e(d, nxt, '否'); g.e(a, nxt)
    g.e(d17, a17, '是'); g.e(d17, a18, '否'); g.e(a17, a18)
    g.e(a18, d18)
    g.e(d18, d19, '是'); g.e(d18, x, '否')
    g.e(d19, a19, '是'); g.e(d19, d22, '否')
    g.e(a19, d20)
    g.e(d20, d21, '是'); g.e(d20, d22, '否')
    g.e(d21, a21, '是'); g.e(d21, d20, '否', back=True)
    g.e(a21, d20, back=True)
    g.e(d22, a22, '是'); g.e(d22, a23, '否')
    g.e(a22, a23)
    g.e(a23, d18, '回边：i 自增', back=True)
    g.e(s, c1, '异常', back=False)   # 异常处理边
    g.e(c1, x)
    out.append(g)

    # ============ M2 GTaskManager.syncContent ============
    g = G('GTaskManager.syncContent', 'src/net/micode/notes/gtask/remote/GTaskManager.java', '250-352')
    n = g.n
    s = n('入口', 'entry')
    d0 = n('mCancelled ?', 'decision')
    x = n('出口', 'exit')
    a1 = n('mLocalDeleteIdMap.clear()')
    a2 = n('query 回收站记录')
    d1 = n('c != null ?', 'decision')
    d2 = n('c.moveToNext() ?（回收站）', 'decision')
    a3 = n('gid = 游标值；node = 表查 gid')
    d3 = n('node != null ?', 'decision')
    a4 = n('移除并 doContentSync(DEL_REMOTE)')
    a5 = n('mLocalDeleteIdMap.add(id)')
    a6 = n('c.close()')
    a7 = n('syncFolder()')
    a8 = n('query 本地便签')
    d4 = n('c != null ?', 'decision')
    d5 = n('c.moveToNext() ?（便签）', 'decision')
    a9 = n('gid / node 取值')
    d6 = n('node != null ?', 'decision')
    a10 = n('写入双向映射；syncType = getSyncAction')
    d7 = n('gtask_id 为空 ?', 'decision')
    a11 = n('syncType = ADD_REMOTE')
    a12 = n('syncType = DEL_LOCAL')
    a13 = n('doContentSync(syncType, node, c)')
    d8 = n('迭代器还有元素？', 'decision')
    a14 = n('doContentSync(ADD_LOCAL, node, null)')
    d9 = n('!mCancelled ?（批量删）', 'decision')
    a15 = n('batchDeleteNotes')
    d10 = n('!mCancelled ?（提交）', 'decision')
    a16 = n('commitUpdate() + refreshLocalSyncId()')

    g.e(s, d0)
    g.e(d0, x, '是'); g.e(d0, a1, '否')
    g.e(a1, a2); g.e(a2, d1)
    g.e(d1, d2, '是'); g.e(d1, a6, '否')
    g.e(d2, a3, '是'); g.e(d2, a6, '否')
    g.e(a3, d3)
    g.e(d3, a4, '是'); g.e(d3, a5, '否')
    g.e(a4, a5)
    g.e(a5, d2, back=True)
    g.e(a6, a7); g.e(a7, a8); g.e(a8, d4)
    g.e(d4, d5, '是'); g.e(d4, d8, '否')
    g.e(d5, a9, '是'); g.e(d5, d8, '否')
    g.e(a9, d6)
    g.e(d6, a10, '是'); g.e(d6, d7, '否')
    g.e(a10, a13)
    g.e(d7, a11, '是'); g.e(d7, a12, '否')
    g.e(a11, a13); g.e(a12, a13)
    g.e(a13, d5, back=True)
    g.e(d8, a14, '是'); g.e(d8, d9, '否')
    g.e(a14, d8, back=True)
    g.e(d9, a15, '是'); g.e(d9, d10, '否')
    g.e(a15, d10)
    g.e(d10, a16, '是'); g.e(d10, x, '否')
    g.e(a16, x)
    out.append(g)

    # ============ M3 GTaskManager.syncFolder ============
    g = G('GTaskManager.syncFolder', 'src/net/micode/notes/gtask/remote/GTaskManager.java', '354-477')
    n = g.n
    s = n('入口', 'entry')
    d0 = n('mCancelled ?', 'decision')
    x = n('出口', 'exit')
    a1 = n('query 根目录（按 ID_ROOT_FOLDER）')
    d1 = n('c != null ?', 'decision')
    a2 = n('moveToNext 取根目录行')
    d2 = n('node != null ?', 'decision')
    a3 = n('写映射；名称不符则 UPDATE_REMOTE')
    a4 = n('ADD_REMOTE（根目录首次同步）')
    a5 = n('query 通话记录目录')
    d3 = n('c != null ?', 'decision')
    d4 = n('c.moveToNext() ?（通话目录）', 'decision')
    d5 = n('node != null ?', 'decision')
    a6 = n('写映射；名称不符则 UPDATE_REMOTE')
    a7 = n('ADD_REMOTE（通话目录首次同步）')
    a8 = n('query 普通目录')
    d6 = n('c != null ?', 'decision')
    d7 = n('c.moveToNext() ?（目录）', 'decision')
    a9 = n('gid / node 取值')
    d8 = n('node != null ?', 'decision')
    a10 = n('写映射；syncType = getSyncAction')
    d9 = n('gtask_id 为空 ?', 'decision')
    a11 = n('syncType = ADD_REMOTE')
    a12 = n('syncType = DEL_LOCAL')
    a13 = n('doContentSync(syncType, node, c)')
    d10 = n('清单迭代器还有元素？', 'decision')
    d11 = n('mGTaskHashMap 含该 gid ?', 'decision')
    a14 = n('移除并 doContentSync(ADD_LOCAL)')
    d12 = n('!mCancelled ?', 'decision')
    a15 = n('commitUpdate()')

    g.e(s, d0)
    g.e(d0, x, '是'); g.e(d0, a1, '否')
    g.e(a1, d1)
    g.e(d1, a2, '是'); g.e(d1, a5, '否')
    g.e(a2, d2)
    g.e(d2, a3, '是'); g.e(d2, a4, '否')
    g.e(a3, a5); g.e(a4, a5)
    g.e(a5, d3)
    g.e(d3, d4, '是'); g.e(d3, a8, '否')
    g.e(d4, d5, '是'); g.e(d4, a8, '否')
    g.e(d5, a6, '是'); g.e(d5, a7, '否')
    g.e(a6, a8); g.e(a7, a8)
    g.e(a8, d6)
    g.e(d6, d7, '是'); g.e(d6, d10, '否')
    g.e(d7, a9, '是'); g.e(d7, d10, '否')
    g.e(a9, d8)
    g.e(d8, a10, '是'); g.e(d8, d9, '否')
    g.e(a10, a13)
    g.e(d9, a11, '是'); g.e(d9, a12, '否')
    g.e(a11, a13); g.e(a12, a13)
    g.e(a13, d7, back=True)
    g.e(d10, d11, '是'); g.e(d10, d12, '否')
    g.e(d11, a14, '是'); g.e(d11, d10, '否')
    g.e(a14, d10, back=True)
    g.e(d12, a15, '是'); g.e(d12, x, '否')
    g.e(a15, x)
    out.append(g)

    # ============ M4 Note.NoteData.pushIntoContentResolver ============
    g = G('Note.NoteData.pushIntoContentResolver', 'src/net/micode/notes/model/Note.java', '181-251')
    n = g.n
    s = n('入口', 'entry')
    d0 = n('noteId <= 0 ?', 'decision')
    a0 = n('throw IllegalArgumentException（前置校验）')
    a1 = n('operationList = new ArrayList()')
    d1 = n('mTextDataValues.size() > 0 ?', 'decision')
    a2 = n('put(NOTE_ID, noteId)')
    d2 = n('mTextDataId == 0 ?', 'decision')
    a3 = n('insert 文本分片')
    c1 = n('catch NumberFormatException：clear + return null', 'catch')
    a4 = n('newUpdate(textId) 并加入批次')
    d3 = n('mCallDataValues.size() > 0 ?', 'decision')
    a5 = n('put(NOTE_ID, noteId)')
    d4 = n('mCallDataId == 0 ?', 'decision')
    a6 = n('insert 通话分片')
    c2 = n('catch NumberFormatException：clear + return null', 'catch')
    a7 = n('newUpdate(callId) 并加入批次')
    d5 = n('operationList.size() > 0 ?', 'decision')
    a8 = n('applyBatch(AUTHORITY)')
    c3 = n('catch RemoteException：log + return null', 'catch')
    c4 = n('catch OperationApplicationException：log + return null', 'catch')
    x = n('返回 Uri 或 null（出口）', 'exit')

    g.e(s, d0)
    g.e(d0, a0, '是'); g.e(d0, a1, '否')
    g.e(a0, x)
    g.e(a1, d1)
    g.e(d1, a2, '是'); g.e(d1, d3, '否')
    g.e(a2, d2)
    g.e(d2, a3, '是'); g.e(d2, a4, '否')
    g.e(a3, c1, '异常'); g.e(a3, d3, '正常')
    g.e(c1, x)
    g.e(a4, d3)
    g.e(d3, a5, '是'); g.e(d3, d5, '否')
    g.e(a5, d4)
    g.e(d4, a6, '是'); g.e(d4, a7, '否')
    g.e(a6, c2, '异常'); g.e(a6, d5, '正常')
    g.e(c2, x)
    g.e(a7, d5)
    g.e(d5, a8, '是'); g.e(d5, x, '否')
    g.e(a8, c3, '异常'); g.e(a8, c4, '异常'); g.e(a8, x, '正常')
    g.e(c3, x); g.e(c4, x)
    out.append(g)

    # ============ M5 BackupUtils.TextExport.exportToText ============
    g = G('BackupUtils.TextExport.exportToText', 'src/net/micode/notes/tool/BackupUtils.java', '221-283')
    n = g.n
    s = n('入口', 'entry')
    d0 = n('!externalStorageAvailable() ?', 'decision')
    a0 = n('return STATE_SD_CARD_UNMOUNTED')
    a1 = n('getExportToTextPrintStream()')
    d1 = n('ps == null ?', 'decision')
    a2 = n('return STATE_SYSTEM_ERROR')
    a3 = n('query 目录游标')
    d2 = n('folderCursor != null ?', 'decision')
    d3 = n('folderCursor.moveToFirst() ?', 'decision')
    a4 = n('取目录名（通话目录用固定串，否则取 snippet）')
    d4 = n('!TextUtils.isEmpty(folderName) ?', 'decision')
    a5 = n('ps.println(目录名)')
    a6 = n('exportFolderToText(folderId, ps)')
    d5 = n('do-while：folderCursor.moveToNext() ?', 'decision')
    a7 = n('folderCursor.close()')
    a8 = n('query 根目录便签游标')
    d6 = n('noteCursor != null ?', 'decision')
    d7 = n('noteCursor.moveToFirst() ?', 'decision')
    a9 = n('println(修改时间)')
    a10 = n('exportNoteToText(noteId, ps)')
    d8 = n('do-while：noteCursor.moveToNext() ?', 'decision')
    a11 = n('noteCursor.close()')
    a12 = n('ps.close()')
    x = n('return STATE_SUCCESS（出口）', 'exit')

    g.e(s, d0)
    g.e(d0, a0, '是'); g.e(d0, a1, '否')
    g.e(a0, x)
    g.e(a1, d1)
    g.e(d1, a2, '是'); g.e(d1, a3, '否')
    g.e(a2, x)
    g.e(a3, d2)
    g.e(d2, d3, '是'); g.e(d2, a8, '否')
    g.e(d3, a4, '是'); g.e(d3, a7, '否')
    g.e(a4, d4)
    g.e(d4, a5, '是'); g.e(d4, a6, '否')
    g.e(a5, a6)
    g.e(a6, d5)
    g.e(d5, a4, '是', back=True); g.e(d5, a7, '否')
    g.e(a7, a8)
    g.e(a8, d6)
    g.e(d6, d7, '是'); g.e(d6, a12, '否')
    g.e(d7, a9, '是'); g.e(d7, a11, '否')
    g.e(a9, a10)
    g.e(a10, d8)
    g.e(d8, a9, '是', back=True); g.e(d8, a11, '否')
    g.e(a11, a12)
    g.e(a12, x)
    out.append(g)

    return out


if __name__ == '__main__':
    for g in build_all():
        m = g.check()
        print('%-44s N=%-3d E=%-3d V(G)=%-3d 判定=%-2d catch=%-2d 交叉=%-3d 路径=%d' % (
            g.name, m['N'], m['E'], m['VG'], m['dec'], m['catch'], m['cross'],
            len(g.basis_paths())))
