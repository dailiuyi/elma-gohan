# 推荐算法审计采纳说明

> 原审计基于旧 V0.2 代码。本页记录其在 V0.4 上的实际采纳结果，避免把未采纳方案误写成当前实现。

## 当前版本

- 客观风险：`risk-v0.3.1`
- 用户画像：`taste-v0.1`
- 推荐排序：`recommendation-v0.4.1`

## 已采纳

- 推荐创建时生成非零随机 seed；同分候选使用稳定键排序。
- `selection_snapshot_json` 保存随机前候选、Top-K 标记与探索资格；同一 seed 可重放探索和分组加权抽取。
- 价格异常基线使用产品品类组中位数，组内已知价格少于 5 家时不触发。
- trend 从离散跳变改为“超阈幅度 × 样本量”的连续风险。
- 多样化只保留候选池抽取一层。
- 未设置预算时，不生成“预算合适”理由。
- 评论新鲜度只限制 File Evidence 提高 confidence 的幅度；不会降低高德/百度结构化证据已经提供的基础 confidence。

## 部分采纳

- “不想吃”继续作为用户显式硬排除；输入先做 NFKC 归一化。
- 多字关键词可命中店名或品类；单字只允许精确命中品类 label/code，避免店名子串误伤。

## 未采纳

- 不改成绝对步行时间函数：距离继续按用户选择的互斥区间归一化。
- 不恢复旧版 bounded Taste correction：V0.4 的 TasteMatch 继续作为独立 20% 排序分项。

## 已知但暂不调权的问题

评分与数据完整度仍会同时影响 RestaurantQuality、RiskSafety 和 confidence。是否合并这些路径必须以真实接受率、reroll、DISLIKE 与风险校准数据做消融，不在没有样本时凭直觉改权重。

V7 新增 `recommendation_log.random_seed` 与 `selection_snapshot_json`，为后续离线回放提供输入。reroll 耗尽语义、理由体系重构和 Risk 权重校准仍单独立项。
