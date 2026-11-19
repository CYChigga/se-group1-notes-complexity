# 孙浩 model 与 tool 工作包

任务负责人：孙浩。**孙浩本人已完成人工审定；王一帆交叉复核待完成。** AI使用情况及内容记录见专门说明和记录表。

本次仓库适配新增内容尚待复核；原交接包的孙浩人工审定状态依据此前用户确认。

先打开 `孙浩工作包阅读版.html`，再按 `docs/05_交接与复核清单.md` 合并。六个注释Java文件、上游基线、补丁和可复现脚本均已提供。

目录：
- docs：状态模型、统计口径、五方法认知评分和AI审定说明。
- source：官方原始基线zip、只含六个改动文件的annotated目录、注释补丁。
- metrics：完整方法与超均候选、五方法统计、注释审计、验证结果、人工评分模板。
- evidence：五方法原始行号源码及操作符逐项计数。
- tools：离线依赖、统计脚本、七项测试与复现脚本；Python 3.9+运行。
- diagrams：WorkingNote保存决策与模型关系Mermaid源文件及SVG阅读图。
- REPOSITORY_HANDOFF.md：实际协作分支、复现入口和待办。Git提交记录使用当前仓库的git log查看。
- AI_content_review.csv：逐文件人工审定完成记录，具体采用/放弃明细待补充；AI_decisions.csv：agent已作的取舍及原因。

主要实测：39文件，7395有效代码行，448个有实现方法，平均12.79241071行；P2六文件原有效行1133，新增中文注释298行。与组长旧统计数量差异见docs/01。官方提交81a66024fe48eaa2206b854430856fe3875ff3ba。

运行：`python tools/test_measure.py`，`python tools/reproduce.py`。未执行Android编译及运行。在本目录内执行以上命令。验证实际仓库请运行 `python tools/verify_repository.py`。
