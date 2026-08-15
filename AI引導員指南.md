# ALMA 角色人格問卷 — AI 引導員指南

> 給 AI 引導員的操作手冊。AI 透過與人類多輪對話，把抽象的角色描述轉成**可直接送進 ALMA REST `POST /characters` 並成功 init 的 JSON**。
>
> 配套：
> - `問卷.md` — 人類填的完整欄位模板（問卷本體）
> - `問卷解釋.md` — 每個欄位的設計理由、計分依據、ALMA 對應
> - `木婉清的問卷文件.md` — 完整示範（風格範本）
> - `scripts/CreateCharacterExample.json` — **真實可用的 JSON 範本**（本指引以此為標的）

---

## 0. 唯一目標

產出**一份 JSON**，結構跟 `scripts/CreateCharacterExample.json` 完全對齊，送到 `POST /characters` 後伺服器回 `201 created`、角色可立即用 `GET /affect/{name}` 查到。

> **最終輸出就是那個 JSON，不要再加 wrapper、不要解釋。**

---

## 1. 工作風格

- **一次只問一件事**。例外：兩個欄位極度相關（例：對陌生人的 trust ↔ Openness）可合。
- **不長篇解釋理論**。OCC/PAD 數學不講，人類問了再說。
- **每輪 AI 回應 ≤ 80 字**。整份目標 30-50 輪。
- **像有經驗的訪談者**，不像 form-filling bot。
- **所有數值用 JSON number**（不寫成字串）。

---

## 2. 開場（3-5 輪）

收 3 件事：

1. **角色識別** — 名字、來源、簡述
2. **目標應用** — chatbot / 遊戲 NPC / 互動小說 / 純 demo
3. **素材範圍** — 用哪個版本（小說早期、哪部電影、原創哪一版）。**預設不分階段**。

開場模板：
```
嗨，要建一個 ALMA 角色。我會用訪談帶你填完。
三個簡單問題：
1. 角色叫什麼？來源（小說/電影/遊戲/原創）？
2. 一句話描述他/她？
3. 這個角色要丟進什麼應用？
```

---

## 3. Big Five 全域人格（5-10 輪）

順序 O → C → E → A → N（爭議少的先）。

每個 trait 問法：
```
Openness（開放性）：對新經驗、新觀念的反應？
  +1.0 強烈追求新穎
   0.0 中性
  -1.0 強烈抗拒改變
給 -1.0~1.0，最好舉個場景。
```

### Probe 規則

| 人類回答 | AI 動作 |
|---|---|
| 極端值（>0.7 或 <-0.7） | 要求 2-3 個具體場景 |
| 中性值（接近 0） | 接受，標記「無強烈傾向」 |
| 「看情況」 | 追問「在什麼場景傾向哪邊」 |
| 兩 trait 矛盾 | 點出來（例：「你說 C=0.8 但他常遲到耶？」） |
| 提到特定對象的反應 | **標記進 complex_appraisal**，不污染全域 |
| 「不知道」 | 給兩個情境讓他選更像哪個 |
| 答很快沒解釋 | 不追問，標記「待證實」 |

5 個收完時主動讀回確認：
```
全域人格：O=0.0, C=0.6, E=-0.6, A=-0.4, N=0.3。
守規、內向、不輕信人情、中等情緒穩定。
確認？要調哪個？
```

---

## 4. 18 個 Appraisal Rules（10-20 輪）

**完整 18 個 tag** 跟 `CreateCharacterExample.json` 一致。**不要漏**：

### 4.1 完整 tag 對照表

| Tag | 必填欄位 | 禁止欄位 | 範例值 |
|---|---|---|---|
| `GoodEvent` | `desirability` | 不能有 `liking` / `praiseworthiness` / `appealingness` / `likelihood` / `realization` | 0.7 |
| `GoodEventForGoodOther` | `agency="other"`, `desirability`, `liking` | 不能有 `praiseworthiness` / `appealingness` / `likelihood` / `realization` | 0.6, 0.6 |
| `GoodEventForBadOther` | `agency="other"`, `desirability`, `liking` | 同上 | 0.6, -0.4 |
| `BadEvent` | `desirability` | 同 GoodEvent | -0.7 |
| `BadEventForGoodOther` | `agency="other"`, `desirability`, `liking` | 同上 | -0.6, 0.6 |
| `BadEventForBadOther` | `agency="other"`, `desirability`, `liking` | 同上 | -0.6, -0.4 |
| `GoodLikelyFutureEvent` | `desirability`, `likelihood` | 不能有 `liking` / `praiseworthiness` / `appealingness` / `realization` | 0.5, 0.5 |
| `GoodUnlikelyFutureEvent` | `desirability`, `likelihood` | 同上 | 0.5, -0.5 |
| `BadLikelyFutureEvent` | `desirability`, `likelihood` | 同上 | -0.5, 0.5 |
| `BadUnlikelyFutureEvent` | `desirability`, `likelihood` | 同上 | -0.5, -0.5 |
| `EventConfirmed` | `realization=true` | 不能有其他 | (固定) |
| `EventDisconfirmed` | `realization=false` | 不能有其他 | (固定) |
| `GoodActSelf` | `praiseworthiness`, `agency="self"` | 不能有 `desirability` / `liking` / `appealingness` / `likelihood` / `realization` | 0.5 |
| `GoodActOther` | `praiseworthiness`, `agency="other"` | 同上 | 0.5 |
| `BadActSelf` | `praiseworthiness`, `agency="self"` | 同上 | -0.5 |
| `BadActOther` | `praiseworthiness`, `agency="other"` | 同上 | -0.5 |
| `NiceThing` | `appealingness` | 不能有其他 | 0.5 |
| `NastyThing` | `appealingness` | 不能有其他 | -0.5 |

**所有數值範圍 -1.0 ~ 1.0**。

**Exclusive rule**（ALMA `validateEecCombination` 強制）：
- 7 個 appraisal 變數（desirability / praiseworthiness / appealingness / likelihood / realization / liking / agency）每個 tag 只能用對應的 subset
- 多塞會被 400 拒絕
- 例：`GoodEvent` 給了 `liking` 就錯（即使值合理）

### 4.2 分組問法

**不要 18 個分開問**。分 3 組，每組一次問完（每組 2-3 輪）：

**事件組（10 個）— 2-3 輪**
```
事件組。我一次問幾個：

1. 好事降在自己頭上多開心？壞事降在自己頭上多煩惱？（GoodEvent, BadEvent）
2. 別人是好人他獲益時他感受？別人是壞人他獲益時呢？壞人受害時呢？好人受害時呢？
   （Good/BadEventForGood/BadOther × 2 × 2）
3. 預期好事要發生 vs 預期壞事要發生，強烈程度？
   加上「很不可能發生」版本。
   （Good/BadLikely/UnlikelyFutureEvent）

每個給 -1.0~1.0，0.0 = 完全無感。
```

**行為組（4 個）— 1-2 輪**
```
行為組：

1. 自己做好事（GoodActSelf）：多為自己感到驕傲？-1.0~1.0
2. 自己做壞事（BadActSelf）：多自責？-1.0~1.0
3. 別人對他做好事（GoodActOther）：多感謝/欣賞？-1.0~1.0
4. 別人對他做壞事（BadActOther）：多憤怒？-1.0~1.0
```

**物件組（2 個）— 1 輪**
```
物件組：

1. 看到好東西（NiceThing）多愉悅？-1.0~1.0
2. 看到噁心東西（NastyThing）多厭惡？-1.0~1.0
```

**EventConfirmed/Disconfirmed 自動帶**，不用問（值固定）：
```json
"EventConfirmed":    { "realization": true  },
"EventDisconfirmed": { "realization": false }
```

### 4.3 收完讀回

```
18 個 appraisal 收完。唸回重點給人類聽：
- 對自己好事/壞事：desirability +0.7 / -0.7
- 對別人好壞人：...
- 預期事件：...
- 行為：...
- 物件：...
確認？
```

---

## 5. Complex Appraisal（選用，5-10 輪）

**只在人類提到特定對象**才進。例如「木婉清對段譽特別黏」、「對陌生人極度防備但對熟人不」。

### 5.1 七種 kind 對照表

| kind | 必填 | 觸發對象 | signal 有效值 |
|---|---|---|---|
| `self_act` | `kind`, `signal`, `appraisal` | 角色自己 | Act 類型（見下表） |
| `direct_act` | `kind`, `signal`, `performer`, `appraisal` | 別人對他 | Act 類型 |
| `indirect_act` | `kind`, `signal`, `performer`, `appraisal` | 第三人觀察 | Act 類型 |
| `self_emotion` | `kind`, `signal`, `appraisal` | 角色自己表達 | OCC 情緒名（見下表） |
| `indirect_emotion` | `kind`, `signal`, `performer`, `appraisal` | 觀察別人情緒 | OCC 情緒名 |
| `self_mood` | `kind`, `signal`, `appraisal` | 角色自己心情 | Mood word（見下表） |
| `indirect_mood` | `kind`, `signal`, `performer`, `appraisal` | 觀察別人心情 | Mood word |

### 5.1.1 Signal 有效值清單

| 類型 | 來源檔案 | 有效值（用 key，不是 description） |
|---|---|---|
| Act 類型 | `src/de/affect/xml/ActionTypes.java` | 完整清單以該檔為準。常見：`Help`, `Calm`, `Greet`, `Insult`, `Comfort`, `Thank`, `Apologize`, `Attack`, `Defend`, `Praise`, `Blame`, `Comply`, `Refuse`, `Inform`... |
| OCC 情緒名 | `src/de/affect/emotion/EmotionType.java` | 24 種：`Joy`, `Distress`, `HappyFor`, `Gloating`, `Resentment`, `Pity`, `Hope`, `Fear`, `Satisfaction`, `Relief`, `FearsConfirmed`, `Disappointment`, `Pride`, `Admiration`, `Shame`, `Reproach`, `Liking`, `Disliking`, `Gratitude`, `Anger`, `Gratification`, `Remorse`, `Love`, `Hate` |
| Mood word | `src/de/affect/mood/MoodType.java` | 8 象限：`Exuberant`, `Dependent`, `Relaxed`, `Docile`, `Hostile`, `Anxious`, `Disdainful`, `Bored` |

> AI 寫 signal 時，**名稱必須跟 ALMA enum 一致**（區分大小寫）。寫 `joy` / `joyful` / `Happy` 都不行。

### 5.1.2 Complex appraisal 內部限制

兩個**很容易撞牆**的 server 規則（`AlmaRestServer.java:2238, 2058`）：

1. **`appraisal` 物件不可為空** — 至少要有 1 個 tag
2. **1-1-1 限制**：`appraisal` 內最多 1 個 Event tag + 1 個 Action tag + 1 個 Object tag
   - Event 類：8 個（GoodEvent, GoodEventFor*, BadEvent, BadEventFor*, Good/BadLikely/UnlikelyFutureEvent, EventConfirmed, EventDisconfirmed）
   - Action 類：4 個（Good/BadActSelf/Other）
   - Object 類：2 個（NiceThing, NastyThing）
   - 原 ALMA 一個 signal 只給 3 個 EEC slot

3. **Storage key collision**（`L2167-2172`）：同一個 `(performer, signal)` pair 不能重複
   - 18 個 basic appraisal 鎖住 18 個 key
   - complex_appraisal 不能跟 basic 撞（同一 performer + signal）
   - 多個 complex_appraisal entry 之間也不能撞

**範例合法 complex_appraisal**（一段 self_emotion 觸發 Joy）：
```json
{
  "kind": "self_emotion",
  "signal": "Joy",
  "appraisal": {
    "GoodEvent": { "desirability": 0.7 }
  }
}
```

**範例違規**（同 signal 重複 + 多 Event tag）：
```json
{
  "kind": "self_emotion",
  "signal": "Joy",
  "appraisal": {
    "GoodEvent": { "desirability": 0.7 },
    "BadEvent": { "desirability": -0.5 }
  }
}
```
→ 400：「a complex appraisal signal may contain at most one Event tag...」

### 5.2 問法

每條問三件事：
- 誰是 performer？（self 類不需要）
- signal 是什麼？（act 類型 / 情緒名 / mood word）
- appraisal 內部放哪個 18 appraisal tag 的覆寫值？

範例：
```
你提到「段譽在的時候木婉清會活潑起來」。
- 這是木婉清自己（self）對段譽的行為產生反應
- 還是段譽（direct）對木婉清做什麼？
- 還是觀察段譽（indirect）木婉清才有反應？

大概對應 self_act / direct_act / indirect_act 哪個 kind？
```

每條 complex_appraisal 內的 `appraisal` 物件結構跟 §4 一樣（用同一組 18 tag 之一 + 必填欄位），但 **值可以覆寫全域**。

---

## 6. Decay 與 Simulation（3-5 輪）

收 6 個值，用生活化比喻：

| 欄位 | 型別 | 預設 | 語意 | 比喻 |
|---|---|---|---|---|
| `mood.decay_time` | long (ms) | 600000 | 心情回 default 要多久 | 10 分鐘？ |
| `mood.decay_period` | long (ms) | 250 | 心情多頻繁更新 | 4 Hz（每 250ms） |
| `mood.neurotism_stability` | bool | false | 心情會隨機漂移 | true = 神經質易變 |
| `emotion.decay_time` | long (ms) | 20000 | 情緒多久歸零 | 20 秒？ |
| `emotion.decay_period` | long (ms) | 500 | 情緒多頻繁更新 | 2 Hz（每 500ms） |
| `emotion.decay_function` | enum | `linear` | 衰減曲線 | linear / exponential / hyperbolic |
| `emotion.baseline` | float 0.0~1.0 | 0.5 | 無事件時的預設強度 | 0.5 = 中等 |
| `personality.emotion_influence` | float 0.0~1.0 | 0.2 | 人格影響情緒強度 | 0.2 = 弱 |

### 6.1 三個關鍵欄位的語意（AI 必須理解才能引導）

- **`emotion_influence` 0.0~1.0**：
  - `0.0` = 情緒強度**完全由 appraisal 決定**，人格不影響
  - `0.5` = 人格與 appraisal 各半
  - `1.0` = 情緒強度**完全由人格決定**
  - 預設 `0.2`（人格微弱影響）

- **`neurotism_stability` bool**：
  - `false`（預設）= 心情平穩朝 default mood 收斂
  - `true` = 心情會**隨機漂移**（`MoodEngine.randomMoodChange` 介入），適合「情緒化」角色
  - 真實人類可設 false；fictional 極端角色（如焦慮患者）可設 true

- **`decay_function`**：
  - `linear`（預設）= 情緒以固定斜率歸零（例：5 秒內從 1.0 線性降到 0）
  - `exponential` = 開始掉得快、後面拖尾長（例：3 秒內 1.0→0.1，再慢降到 0）
  - `hyperbolic` = S 曲線，開頭慢、中間快、收尾慢（適合「情緒悶燒型」角色）

### 6.2 兩個常見被擋的錯誤

- `mood.decay_period > mood.decay_time` → 400
- `emotion.decay_period > emotion.decay_time` → 400

period 是更新頻率（取樣間隔），time 是總時長。period 必須 ≤ time。

人類沒想法就用預設值。

---

## 7. 收尾：組裝 + 驗證 + 提交（2-3 輪）

### 7.1 組裝 JSON

按 `CreateCharacterExample.json` 的結構組裝：

```json
{
  "name": "<I01>",
  "personality": {
    "openness": <B1>,
    "conscientiousness": <B2>,
    "extraversion": <B3>,
    "agreeableness": <B4>,
    "neurotism": <B5>,
    "emotion_influence": <from §6>,
    "derived": false
  },
  "mood": {
    "decay_time": <from §6>,
    "decay_period": <from §6>,
    "neurotism_stability": <from §6>
  },
  "emotion": {
    "decay_time": <from §6>,
    "decay_period": <from §6>,
    "decay_function": <from §6>,
    "baseline": <from §6>
  },
  "internal_affect_appraisal": <boolean>,
  "appraisal": {
    "GoodEvent": { "desirability": <v> },
    ... (完整 18 個)
  },
  "complex_appraisal": [
    ... (從 §5)
  ]
}
```

**必填**：18 個 appraisal tag 都要在。即使值是 0.0 也要列出，不要省略（REST 會拒絕缺失欄位）。

### 7.2 自我驗證 checklist

送出前 AI 必須自查：

- [ ] `name` 1-80 字元，無 ` - `（保留分隔符）
- [ ] Big Five 5 個值都在 -1.0~1.0
- [ ] 18 個 appraisal tag 都在
- [ ] `EventConfirmed.realization=true`、`EventDisconfirmed.realization=false` 固定
- [ ] 4 個 `agency="other"` appraisal 有 `liking` 欄位
- [ ] 4 個 Act appraisal 有 `agency` 欄位（self/other）
- [ ] **每個 tag 符合 §4.1 的 exclusive rule**（不能多塞欄位）
- [ ] 所有數值是 JSON number 不是字串
- [ ] `mood.decay_period <= mood.decay_time`
- [ ] `emotion.decay_period <= emotion.decay_time`
- [ ] decay 參數 > 0
- [ ] **每條 complex_appraisal 的 `appraisal` 不可為空**
- [ ] **每條 complex_appraisal 的 `appraisal` 內：1 Event tag + 1 Action tag + 1 Object tag 上限**（1-1-1 規則）
- [ ] **complex_appraisal 的 (performer, signal) pair 沒跟其他 entry 或 basic appraisal 撞**
- [ ] `signal` 名稱跟 ALMA enum 大小寫一致
- [ ] 若 `internal_affect_appraisal=true`，至少有一條 `self_emotion` 或 `self_mood` complex_appraisal

### 7.3 提交

```
問完了。JSON 如下：

{ ... }

直接送：
POST http://localhost:8081/characters
Content-Type: application/json
{ ... }

預期回 201 + 角色已建立。要我幫你跑嗎？或你自己送？
```

---

## 8. 常見陷阱

| 陷阱 | 症狀 | 防範 |
|---|---|---|
| 漏 18 個 appraisal tag | REST 拒絕、MissingKey | 收完時用 checklist 逐個對 |
| 特定對象反應寫進 Big Five | 「對段譽黏踢踢」污染全域 Openness | 進 complex_appraisal，不污染全域 |
| 不分版本 | 小說早期 vs 黑化後混著 | 開場先鎖定一個版本 |
| 用影視演員當基準 | 邵氏 vs TVB vs 大陸差很多 | 確認「用哪版的詮釋」 |
| decay 設太短/太長 | 1 秒 = 無情緒；24 小時 = 永遠忘不了 | 用生活化比喻引導 |
| baseline 給 1.0 | 無時無刻都強烈情緒 | 提醒「baseline 是無事件時的強度」 |
| 開 internal_affect_appraisal 沒對應 SelfEmotion/SelfMood | 500ms timer 報錯 | 開前確認有對應 rule |
| 給極值但舉不出 3 個場景 | 數值飄 | probe 規則：>0.7 要 2-3 場景 |
| 數值寫成字串 `"0.7"` | REST 拒絕 | 一律 JSON number |
| `name` 含 ` - ` | REST 拒絕 | 字元限制 `[A-Za-z0-9_. -]`、避免 ` - ` |
| `decay_period > decay_time` | 400 | period（更新頻率）必須 ≤ time（總時長）|
| complex_appraisal `appraisal` 為空 | 400 | 至少 1 個 tag |
| complex_appraisal 塞 2 個 Event tag | 400：「may contain at most one Event tag...」| 1-1-1 限制 |
| complex_appraisal `(performer, signal)` 跟 basic 撞 | 400：「conflicts with another ALMA rule at...」| 先檢查 18 個 basic appraisal 跟 complex entries 不撞同 pair |
| `GoodEvent` 多塞 `liking` | 400 | 嚴格遵守 §4.1 exclusive rule |
| `signal` 寫成小寫 (`joy` / `relaxed`) | 400 | 必須跟 ALMA enum 大小寫一致 |

---

## 9. 完整 JSON 範本（可作預設骨架）

```json
{
  "name": "CharacterName",
  "personality": {
    "openness": 0.0,
    "conscientiousness": 0.0,
    "extraversion": 0.0,
    "agreeableness": 0.0,
    "neurotism": 0.0,
    "emotion_influence": 0.2,
    "derived": false
  },
  "mood": {
    "decay_time": 600000,
    "decay_period": 250,
    "neurotism_stability": false
  },
  "emotion": {
    "decay_time": 20000,
    "decay_period": 500,
    "decay_function": "linear",
    "baseline": 0.5
  },
  "internal_affect_appraisal": false,
  "appraisal": {
    "GoodEvent": { "desirability": 0.5 },
    "GoodEventForGoodOther": { "agency": "other", "desirability": 0.5, "liking": 0.5 },
    "GoodEventForBadOther": { "agency": "other", "desirability": 0.5, "liking": -0.5 },
    "BadEvent": { "desirability": -0.5 },
    "BadEventForGoodOther": { "agency": "other", "desirability": -0.5, "liking": 0.5 },
    "BadEventForBadOther": { "agency": "other", "desirability": -0.5, "liking": -0.5 },
    "GoodLikelyFutureEvent": { "desirability": 0.5, "likelihood": 0.5 },
    "GoodUnlikelyFutureEvent": { "desirability": 0.5, "likelihood": -0.5 },
    "BadLikelyFutureEvent": { "desirability": -0.5, "likelihood": 0.5 },
    "BadUnlikelyFutureEvent": { "desirability": -0.5, "likelihood": -0.5 },
    "EventConfirmed": { "realization": true },
    "EventDisconfirmed": { "realization": false },
    "GoodActSelf": { "praiseworthiness": 0.5, "agency": "self" },
    "GoodActOther": { "praiseworthiness": 0.5, "agency": "other" },
    "BadActSelf": { "praiseworthiness": -0.5, "agency": "self" },
    "BadActOther": { "praiseworthiness": -0.5, "agency": "other" },
    "NiceThing": { "appealingness": 0.5 },
    "NastyThing": { "appealingness": -0.5 }
  },
  "complex_appraisal": []
}
```

> 這份是「全中性」骨架。要把 §3-§6 收集到的值填進去。complex_appraisal 預設空陣列，有需要再 append。

---

## 10. 範例對話骨架

```
Human: 我想建一個木婉清。
AI:    嗨，要建一個 ALMA 角色。先三個簡單問題：
       1. 角色叫什麼？來源？
       2. 一句話描述他/她？
       3. 這個角色要丟進什麼應用？
Human: 名字就叫木婉清。《天龍八部》金庸小說。表面冷傲、內心專一的女子。
       chatbot 場景，玩家會跟她對話。
AI:    OK，鎖定「早期至中期」的形象（不用結局反推）？
       開始 Big Five。
       Openness（開放性）：對新經驗、新觀念的反應？-1.0~1.0，舉個場景。
Human: 0.0。她就是那個時代的價值觀。
AI:    收到。Conscientiousness（盡責性）？
Human: 0.6。她對自己訂的規矩很死守，像戴面紗那條。
AI:    Extraversion（外向性）？
Human: -0.6。她不主動社交，但段譽在的時候會活潑起來。
AI:    等等，「段譽在的時候」是特定對象反應，不該寫進全域。
       先給 -0.6 我們記下來，後面用 complex_appraisal 處理段譽。
       同意繼續？
Human: 同意。
AI:    Agreeableness（親和性）？
Human: -0.4。對陌生人低信任，但對段譽可以黏踢踢。
AI:    又是段譽的問題。OK 全域給 -0.4，段譽進 complex。
       Neuroticism（神經質）？
Human: 0.3。中等。
AI:    Big Five 完成：O=0.0, C=0.6, E=-0.6, A=-0.4, N=0.3。
       確認？
Human: OK。
AI:    事件組。我一次問幾個：
       1. 好事降在自己頭上她多開心？壞事降在自己頭上她多煩惱？-1.0~1.0
       2. 別人是好人她獲益？別人是壞人她獲益？壞人受害？好人受害？四個值
       3. 預期好事要發生 / 預期壞事要發生 / 不太可能好事 / 不太可能壞事
Human: 1. 0.7 / -0.7  2. 0.5 / 0.6 / -0.5 / 0.4  3. 0.5 / -0.5 / 0.3 / -0.3
AI:    行為組：自己做/壞事，別人對她做/壞事。praiseworthiness 四個值。
Object 組：NiceThing, NastyThing。
Human: GoodActSelf=0.4, BadActSelf=-0.6, GoodActOther=0.5, BadActOther=-0.5, NiceThing=0.3, NastyThing=-0.4
AI:    18 個 appraisal 收完。最後是 complex：段譽在的時候她會活潑。
       對段譽這個觸發，是她「自己內心」反應（self_emotion）、
       還是「段譽對她做什麼」（direct_act）？
Human: 是她自己看到段譽就開心。
AI:    那 kind = self_emotion。signal 是哪個情緒名？Joy 還是別的？
Human: Joy。
AI:    appraisal 內部要覆寫哪個 tag？值多少？
       一般來說是 GoodEvent 的 desirability。要比全域的 0.7 高一點嗎？
Human: 段譽的話高一些，0.9。
AI:    收到。最後 decay 跟 simulation 參數：
       1. 情緒殘留時間：多久歸零？20 秒？1 分鐘？
       2. 心情殘留時間：多久回 default？10 分鐘？1 小時？
       3. decay function：linear / exponential / hyperbolic？預設 linear
       4. emotion.baseline：無事件時強度 0.0~1.0？預設 0.5
       5. emotion_influence：人格影響強度 0.0~1.0？預設 0.2
       6. neurotism_stability：心情會隨機漂移？預設 false
Human: 全部用預設就好。
AI:    收到。組裝 JSON 給你看：
       {
         "name": "木婉清",
         "personality": { "openness": 0.0, "conscientiousness": 0.6, ... },
         "mood": { "decay_time": 600000, "decay_period": 250, ... },
         ...
         "complex_appraisal": [
           {
             "kind": "self_emotion",
             "signal": "Joy",
             "appraisal": { "GoodEvent": { "desirability": 0.9 } }
           }
         ]
       }
       確認？
... (確認後跑 §7.2 checklist + 提交)
```

---

## 11. 邊界情況

| 情況 | 處理 |
|---|---|
| 真人角色 | 一樣可建，提醒「這不是心理測量」 |
| 多版本角色 | 鎖一個版本，`personality.derived=false` 標示 |
| 極簡設定（「就一般人」） | 用 §9 全中性骨架，5 分鐘收工 |
| 完全原創、沒素材 | 問 5 個「極端情境」反應代替文獻分析 |
| 群體（多角色） | 收完第一個就停，問「還有別的角色嗎」，分開建 |
| 問到一半人類說「先這樣」 | 接受，用目前值組 JSON，未填欄位補預設 0.0 |
| 開 internal_affect_appraisal | 必須在 complex_appraisal 至少加一條 self_emotion 或 self_mood |
| 數值溢出範圍 | 在送出前 normalize 到 -1.0~1.0 |
