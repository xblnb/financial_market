# 虚拟经济 ↔ 实体经济：CZCE 实物交割

## 目标

期货不再只有现金结算：绑定交割方块后，到期合约按仓单实物交收，多头拿货、空头交货，货款按交割价结算；货物/资金流再回写商品市场。

## 双轨

```text
到期合约
 ├─ 有交割方块 + 资金/库存满足 → 实物交割
 └─ 无交割方块 / 预检失败      → CZCE 现金交割兜底
```

- 交割方块：`DeliveryBlockEntity`，手持带 `bound_contract` NBT 的物品右键绑定。
- CZCE 模式由 `tryCzcePhysicalDelivery` 接管；legacy 物品期货保留原路径。

## 实物交割流程

1. 合约到达最后交易日之后，`MarketEngine.runDeliveryBlocks` 在 CZCE tick 之前触发。
2. 多头：
   - 释放保证金到账户；
   - 按 `multiplier × longLots` 把标的物品放入交割方块库存；
   - 按交割价从账户/玩家钱包支付货款；
   - 物品从商品市场“需求/库存”侧消耗。
3. 空头：
   - 释放保证金；
   - 从玩家背包扣除标的物品；
   - 已交付部分收取货款，不足部分按交割价现金补偿；
   - 已交付货物回写商品市场“供给”侧。
4. 双方持仓清空，账户余额扫回玩家钱包。
5. 合约标记 `physicallyDelivered`，`settleDay` 跳过现金交割。

## 预检

- 多头必须满足：
  ```text
  账户现金 + 玩家钱包 ≥ 交割价 × multiplier × longLots
  ```
  不满足则整笔退回现金交割，避免部分交割。
- 空头允许库存不足，缺口按交割价现金补偿。
- 交割方块必须已绑定储蓄卡且 `tradeBlockEnabled`。

## 实体经济反馈

- `CommodityMarket.applyConsumption(item, qty)`：多头提货，减少市场可流通库存。
- `CommodityMarket.applyProduction(item, qty)`：空头交货，增加市场供给。
- 商品价格继续进入 CZCE spot / 股票行业成本传导。

## 统计

`CzceFuturesMarket` 记录：

```text
physicalDeliveryLots
physicalDeliveryQuantity
deliveredContracts
```

通过 `GET /api/czce` 返回，并写入 CZCE NBT（随 H2 `engine_kv.engine_main` 持久化）。

## 边界

1. 交割方块库存满时，超出部分目前没有自动掉落/扩容。
2. 空头玩家离线时无法从其背包扣货，缺口走现金补偿。
3. 物理交割只支持 `ProductSpec.itemId` 为物品的品种；流体/服务类品种走现金。
4. 没有仓单二级市场、质检升贴水、交割库升贴水。
5. 未做真实服务器双人交割 smoke；目前为编译 + 纯宏观探针验证。
