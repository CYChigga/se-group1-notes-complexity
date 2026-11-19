# WorkingNote状态与生命周期

孙浩本人已完成人工审定，王一帆交叉复核待完成。源码定位均指固定基线，注释版行号会向后移动。

## 两层职责

WorkingNote.java 34–368行承载编辑会话：正文、模式、提醒、背景、小部件及监听器；Note.java 33–253行承载主表和正文/通话数据表的待写差量。真正存储由ContentResolver访问Notes Provider完成。编辑对象并非数据库实体的实时镜像。

## 状态向量与保存条件

用I=(mNoteId>0)、E=TextUtils.isEmpty(mContent)、D=mNote.isLocalModified()、X=mIsDeleted表示状态。isWorthSaving原始219–226行等价于：

```text
值得保存 = !X && (( !I && !E ) || ( I && D ))
```

新草稿只看正文非空，不要求D；已有便签只看D，不要求正文非空。四个量不是四个独立枚举，也不能将X覆盖前的脏状态永久丢弃。

|场景|条件|saveNote行为|
|---|---|---|
|空新草稿|I=0,E=1,X=0|返回false，不创建主表记录|
|非空新草稿|I=0,E=0,X=0|先创建主表主键；主键为零则false|
|已有未改便签|I=1,D=0,X=0|返回false，这不是写入故障|
|已有脏便签|I=1,D=1,X=0|进入同步流程，即使正文清空也可保存|
|标记删除|X=1|返回false；markDeleted本身不删除数据库|
|撤销删除标记|markDeleted(false)|恢复由主键正文差量决定的保存资格|

## 加载与编辑

新便签构造器105–115行设置主键零；createEmptyNote 177–183行再设置背景和小部件信息。加载入口186–188行调用已有记录构造器，loadNote 127–147行读主表，loadNoteData 149–175行按MIME恢复正文及关联行主键。空游标对象与零行游标的处理不同，不能把load描述为对所有不存在记录都可靠返回null。

setWorkingText 284–289行先比较新旧正文；变化才登记正文差量。Note.setNoteValue及NoteData字段设置会更新主表本地修改标志与时间。getModifiedDate读取的会话缓存未随每次修改刷新，不能当作落库后的最新时间。

setBgColorId在回调之后才登记差量；setCheckListMode先用旧值和新值通知，再更新mMode；setAlertDate即使时间未变，也可能触发监听器。这些调用顺序会影响回调可观察的状态。

convertToCallNote 291–295行把父目录差量设置为通话文件夹，但没有同步更新mFolderId缓存；图表和报告应区分“待写目录”与“会话缓存目录”。

## 保存与失败语义

saveNote 190–213行在通过资格判断后，必要时创建主键，再调用Note.syncNote。源码忽略syncNote的boolean返回值，随后仍可能通知小部件并返回true。因此不能画出“true必然代表数据库同步成功”的状态转换。

Note.syncNote 103–133行在主表更新零行后仍继续，并清空主表差量。NoteData.pushIntoContentResolver 181–251行对新正文/通话记录即时insert，对已有记录积累applyBatch操作，完成过程中清理差量；若只有新插入而没有更新批次，最后也返回null。这意味着失败时是否仍脏、是否已经部分写入，需要按实际路径判断，不能一概返回到“已有干净状态”。

应考虑的改进包括：透传并细化同步结果；成功后再清理差量；统一事务边界；明确缓存更新时间。根据分工，本次只补注释和分析，不实施逻辑修复。

## Agent核对用例及人工复核任务

1. 新草稿空正文即使有背景差量仍不保存。
2. 新草稿非空且未删才尝试取得主键。
3. 已有便签正文清空且有差量仍具保存资格。
4. 已有无差量返回false不能误报为故障。
5. markDeleted(true)禁止保存，但不直接删除行。
6. markDeleted(false)后重新按底层差量计算资格。
7. 底层syncNote失败未被saveNote透传。
8. 只有新数据插入时push返回null不等于从未写入。

上述是静态走读推导，不是Android运行测试记录。孙浩已完成人工审定；用户尚未提供逐条核验过程或设备运行记录，不额外宣称已完成运行测试。
