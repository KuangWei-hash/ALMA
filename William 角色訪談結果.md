# William 角色訪談結果

> 對應 JSON：`William.json`
> 對應訪談過程：`角色訪談過程全紀錄.md`
> 生成時間：2026-08-15
> AI 引導員：Mavis / mvs_d0bb0ef941934297a1d54b23fbcc4d85
> 訪談輪數：~28 輪
> probe 次數：7 次（其中 5 次值被調整）

## 0. 角色核心假設（驗收基準）

**William** 是真實世界一位普通工人、兩個孩子的父親與丈夫，性格克制、責任感強、社交節能、情緒偏內斂。OCC appraisal 反映他對「日常小事」的反應強度（中等偏淡），而非最大可能強度。對家人（內圈）的愛是唯一明確的「看著別人」觸發規則。

## 1. 識別

### I01. 角色名稱
- 填入：`William`
- 證據類型：D
- 信心度：高
- 為何這樣填：人類自報姓名
- 為何不是其他：n/a

### I02. 角色來源
- 填入：真實世界
- 證據類型：D
- 信心度：高
- 為何這樣填：人類明確說「真人角色」
- 為何不是其他：n/a

### I03. 角色簡述
- 填入：一個普通的工人，兩個孩子的父親，丈夫
- 證據類型：D
- 信心度：高
- 為何這樣填：人類自述
- 對模型的意義：設定人格基線為「忙碌、責任為先、社交低耗」

### I04. 應用
- 填入：遊戲 NPC
- 證據類型：D
- 信心度：高
- 對模型的意義：decay 預設合適，無需針對 chat/敘事調參

### I05. 素材範圍
- 填入：現在的（單一時間點）
- 證據類型：D
- 信心度：高

## 2. Big Five

### O. 開放性
- 填入數值：+0.3
- 證據類型：B
- 信心度：中
- 為何這樣填：普通人對新經驗略開放心
- 為何不是更高或更低：未提到特別愛嘗試或保守
- 對模型的意義：對新奇事件 appraisal 略偏正

### C. 盡責性
- 填入數值：+0.4
- 證據類型：B
- 信心度：高
- 為何這樣填：人類主動提及「負責任的父親與丈夫」
- ⚠️ 改值事件：初填 -0.4，人類修正為 +0.4
- 為何不是更高或更低：未到強迫症程度（C < 0.6）
- 對模型的意義：對責任類事件強度放大

### E. 外向性
- 填入數值：-0.6
- 證據類型：B
- 信心度：高
- 為何這樣填：「普通工人」+ 簡述無社交描述 → 偏內向
- 為何不是更高或更低：典型內向偏強
- 對模型的意義：對社交事件反應偏淡

### A. 親和性
- 填入數值：-0.5
- 證據類型：B
- 信心度：中
- 為何這樣填：未主動提善意行為；簡述顯示以家庭為中心
- 為何不是更高或更低：對家人有愛（complex rule 證明）但對外人親和偏淡
- 對模型的意義：對陌生人的好事件反應偏淡

### N. 神經質
- 填入數值：+0.2
- 證據類型：B
- 信心度：中
- 為何這樣填：「個性克制」+ 普通工人
- ⚠️ 改值事件：初填 +0.4，人類修正為 +0.2
- 為何不是更高或更低：克制 → 不易焦慮
- 對模型的意義：情緒基線平穩

## 3. 18 個 Appraisal Rules

### GoodEvent
- 填入：desirability=+0.2
- 證據類型：B
- 信心度：中
- 為何這樣填：典型 appraisal（日常生活強度，非最大可能）
- ⚠️ 改值事件：人類主動從 +0.6 修正為 +0.2，理由「在生活中你根本遇不到那麼多大善大惡」
- 對模型的意義：好事讓他高興但不會狂喜

### BadEvent
- 填入：desirability=-0.3
- 證據類型：B
- 信心度：中
- 為何這樣填：典型 appraisal
- ⚠️ 改值事件：人類主動從 -0.7 修正為 -0.3
- 對模型的意義：壞事讓他煩但不崩潰

### GoodEventForGoodOther
- 填入：desirability=+0.15, agency=other
- 證據類型：B
- 信心度：低
- 為何這樣填：對喜歡的人好事 → 略為高興
- 對模型的意義：低強度 HappyFor

### GoodEventForBadOther
- 填入：desirability=+0.2, agency=other
- 證據類型：B
- 信心度：低
- 為何這樣填：對討厭的人好事 → 略反感（desirability 符號由 event 決定）
- 對模型的意義：低強度 Resentment

### BadEventForGoodOther
- 填入：desirability=-0.25, agency=other
- 證據類型：B
- 信心度：低
- 為何這樣填：對喜歡的人壞事 → 同情
- 對模型的意義：低強度 SorryFor

### BadEventForBadOther
- 填入：desirability=-0.3, agency=other
- 證據類型：B
- 信心度：低
- 為何這樣填：對討厭的人壞事 → 冷淡
- 對模型的意義：低強度 Gloating

### GoodLikelyFutureEvent
- 填入：desirability=+0.6, likelihood=+0.5
- 證據類型：B
- 信心度：中
- ⚠️ 改值事件：初填 +0.2，人類重新用「事本身好壞」解讀 → +0.6
- 對模型的意義：好事很可能發生 → 強度高（Hope）

### GoodUnlikelyFutureEvent
- 填入：desirability=+0.1, likelihood=-0.5
- 證據類型：B
- 信心度：中
- ⚠️ 改值事件：初填 -0.05，人類重新用「事本身好壞」解讀 → +0.1
- 對模型的意義：好事不太可能 → 微弱 Hope

### BadLikelyFutureEvent
- 填入：desirability=-0.5, likelihood=+0.5
- 證據類型：B
- 信心度：中
- 對模型的意義：壞事很可能 → 中等 Fear

### BadUnlikelyFutureEvent
- 填入：desirability=-0.9, likelihood=-0.5
- 證據類型：B
- 信心度：高
- ⚠️ 改值事件：初填 +0.1（誤用 liking 邏輯）→ 修正 -0.5（schema 一致，engine 算 Relief）→ 最終 -0.9（理由「壞事很不想」）
- 對模型的意義：壞事不太可能 → 強 Relief

### EventConfirmed
- 填入：realization=true（schema 固定）
- 證據類型：D
- 信心度：高
- 為何這樣填：REST schema 強制

### EventDisconfirmed
- 填入：realization=false（schema 固定）
- 證據類型：D
- 信心度：高
- 為何這樣填：REST schema 強制

### GoodActSelf
- 填入：praiseworthiness=+0.2, agency=self
- 證據類型：B
- 信心度：中
- ⚠️ 改值事件：初填 +0.8，人類修正為 +0.2（typical appraisal）
- 對模型的意義：自己做對事 → 略 Pride

### GoodActOther
- 填入：praiseworthiness=+0.3, agency=other
- 證據類型：B
- 信心度：中
- ⚠️ 改值事件：初填 +0.6 → +0.3
- 對模型的意義：別人做好事 → 略 Admiration

### BadActSelf
- 填入：praiseworthiness=-0.2, agency=self
- 證據類型：B
- 信心度：中
- ⚠️ 改值事件：初填 -0.5 → -0.2
- 對模型的意義：自己做錯事 → 略 Shame

### BadActOther
- 填入：praiseworthiness=-0.15, agency=other
- 證據類型：B
- 信心度：中
- ⚠️ 改值事件：初填 -0.9 → -0.15
- 對模型的意義：別人做錯事 → 略弱反應

### NiceThing
- 填入：appealingness=+0.3
- 證據類型：B
- 信心度：中
- 對模型的意義：好東西 → 略 Liking

### NastyThing
- 填入：appealingness=-0.4
- 證據類型：B
- 信心度：中
- 對模型的意義：糟糕東西 → 略 Disliking

### 補：4 個 agency=other 規則的 liking 欄位
- 填入：GoodEventForGoodOther=0.0, GoodEventForBadOther=0.0, BadEventForGoodOther=0.0, BadEventForBadOther=0.0
- 證據類型：C
- 信心度：低
- 為何這樣填：人類未提供 liking 數值，AI 採中性預設
- ⚠️ 待 review：建議下次訪談補問「對喜歡/討厭的人強度差異」

## 4. Complex Appraisal

### Entry 1: kind=self_emotion, signal=Love
- 填入：appraisal={GoodEvent:{desirability:1.0}}
- 證據類型：B
- 信心度：高
- 為何這樣填：人類自述「親人是我最安心的存在」+「指引我，看不懂」+ 給出 signal=Love, desirability=1.0
- 為何只有 1 條：人類確認無其他「看著別人」情緒規則需求
- 對模型的意義：當 appraise 到與親人相關的 GoodEvent 時，觸發 Love 信號

## 5. Decay & Simulation

### mood.decay_time
- 填入：600000ms（10 分鐘）
- 證據類型：D
- 信心度：中
- 為何這樣填：CreateCharacterExample.json 起點值；真實人 mood 持續時間合理
- 對模型的意義：mood 影響會持續數分鐘

### mood.decay_period
- 填入：250ms
- 證據類型：D
- 信心度：中
- 為何這樣填：CreateCharacterExample.json 起點值（4Hz 更新）
- 對模型的意義：mood 每 250ms 重新計算

### mood.neurotism_stability
- 填入：false
- 證據類型：D
- 信心度：高
- 為何這樣填：N=+0.2（克制）→ 心情不會隨機漂移

### emotion.decay_time
- 填入：20000ms（20 秒）
- 證據類型：D
- 信心度：中
- 為何這樣填：CreateCharacterExample.json 起點值

### emotion.decay_period
- 填入：500ms
- 證據類型：D
- 信心度：中
- 為何這樣填：CreateCharacterExample.json 起點值（2Hz 更新）

### emotion.decay_function
- 填入：linear
- 證據類型：D
- 信心度：中
- 為何這樣填：最簡單穩定的衰減曲線

### emotion.baseline
- 填入：0.0
- 證據類型：D
- 信心度：中
- 為何這樣填：CreateCharacterExample.json 實值；無事件時情緒歸零
- ⚠️ 與指南預設 0.5 不一致：經 source 確認，ALMA engine 無 hard-coded baseline，範例檔是 0.0

### personality.emotion_influence
- 填入：0.2
- 證據類型：D
- 信心度：中
- 為何這樣填：CreateCharacterExample.json 起點值（人格微弱影響）

## 6. 整體驗收

- 對陌生人過度反應？**否**（A=-0.5 偏淡，appraisal 強度中等）
- 對親人沒有顯著差異？**有差**（complex_appraisal 規則 signal=Love, 1.0 強度）
- 情緒殘留太久/太短？**合適**（20 秒 emotion / 10 分鐘 mood 對 NPC 合理）
- 初始 mood = "Bored slightly"：Big Five 算出來的真實人 baseline，符合「普通工人、社交低耗」設定

⚠️ 待 review：liking 4 個值是 AI 補的 0.0，信心度低，下次迭代建議補問。
