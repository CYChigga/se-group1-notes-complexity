# P2 仓库交接说明

目标仓库：CYChigga/se-group1-notes-complexity。
工作分支：feature/P2-model-tool；目标分支：main；复核人：王一帆。
团队源码基线：b94cc3908dbdc5784972478431f16c7d6904905a。
README 读取自 main 的 3a23262be81826f19e60b8374c1ef69feedc0fc3。

六个源码文件每个一个 annot(P2) 提交，只增加中文注释，共 298 行。
报告和脚本位于本目录；未修改其他组员源码，未合并或推送 main。

## 复现

在本目录中执行，Python 3.9+，Git用于第三条验证：

```text
python tools/test_measure.py
python tools/reproduce.py
python tools/verify_repository.py
```

依赖随 tools/vendor 提供。test_measure 验证七项边界；reproduce 验证归档基线和六份注释副本；verify_repository 验证实际团队 src 对比团队基线的全部 39 文件，确保只新增六文件中文注释、Java token 和方法度量均不变。未执行 Android 编译或设备测试。

统计实际团队当前源码，可从仓库根执行：

```text
python deliverables/P2-sunhao/tools/measure.py --root . --out <自选输出目录>
```

## 五方法与审定

五个超均方法及原始行号见 metrics/selected_five.csv；请王一帆沿用同一组方法制作 CFG 与 McCabe 度量。当前认知评分是小组自定义双维度量表，非 Sonar 认知复杂度或 Ousterhout 官方数值公式。

AI工具为Codex，新增注释、脚本和分析材料的AI来源及取舍记录见docs/04、AI_content_review.csv和AI_decisions.csv。原交接包孙浩人工审定状态依据此前用户确认；本次仓库适配的验证脚本和文档修订列为待复核，不推定已经人工审定。王一帆交叉复核仍待完成，GitHub账号待提供。

王一帆的P5远端分支目前与原始导入基线相同，没有待孙浩复核的实现提交，不能登记交叉复核通过。组长在双方实际复核后合并PR。

本目录不保留初次独立压缩包的构建器；旧交接包保持原样。新增source/P2-comments.patch来自当前团队分支的六个注释提交，可与Git diff对应。
