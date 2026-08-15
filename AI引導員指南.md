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

產出**三件交付物**：

1. **`<角色名>.json`** — 餵進 `POST /characters` 的 JSON，結構跟 `scripts/CreateCharacterExample.json` 對齊
2. **`角色訪談結果.md`** — 每個值的 5-field 結構（值 + 證據類型 + 信心度 + 為何 + 邊界），給人類 review 用
3. **`角色訪談過程全紀錄.md`** — 訪談全程 Q&A、probe 觸發、值變動時間軸，給反覆探討用

> 結果送進 REST 預期回 201 + 角色立即可用 `GET /affect/{name}` 查到。
> 後兩份**不餵 REST**，純文字檔給人類審核 / 追溯。

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

## 2.5 證據協議（每個值都必填）

> 這是本指引的**核心紀律**。模型每收到一個數值，**必須**同時收齊「值 + 證據」。光有數值、沒有「為什麼」的角色設定不能拿去 production。

每個值 AI 內部維護一個 5-field 結構（最終輸出成 markdown rationale，見 §7.4）：

```
- 填入數值: <v>
- 證據類型: A | B | C | D
- 信心度:   高 | 中 | 低
- 為何這樣填: <一句話正面推論>
- 為何不是更高或更低: <一句話邊界推論>
```

### 證據類型 A/B/C/D（取自木婉清範例的 0.1 節）

| 標籤 | 意義 | 企劃使用方式 |
|---|---|---|
| `A` 原作高度支持 | 多個共同情節或核心設定直接支持 | 可較有信心填到明顯或極端值 |
| `B` 綜合推論 | 由多個行為推導，原作沒有直接量化 | 可偏高/偏低，但應保留邊界 |
| `C` 證據不足 | 涉及原作很少呈現的面向 | 優先填中性，不要硬猜 |
| `D` 企劃調參 | 為應用節奏（chatbot 對話節奏、遊戲互動節奏）選擇 | 必須經實機測試，不視為角色心理事實 |

### 信心度 高/中/低

- **高**：多個獨立證據指向同方向，矛盾少
- **中**：有支持證據但也有反例
- **低**：證據少或詮釋空間大

### 為何不是更高或更低（邊界推論）

**這欄最容易漏**，但最重要。它強迫 AI 說明：「為什麼不是 +0.5 而是 +0.7」、「為什麼不是 0 而是 0.3」。沒有邊界推論，數值就是猜的。

範例（Openness 收 0.0）：
```
- 填入數值: 0.0
- 證據類型: C
- 信心度: 中
- 為何這樣填: 她的成長環境封閉，沒有明顯追求新觀念或主動抗拒的傾向
- 為何不是更高或更低: 不填 -0.5（她不是頑固守舊）；不填 +0.5（她沒有特別求新）；
  證據不足採中性
```

### 收值流程（每次 AI 收一個數值）

> **AI 是訪談者，不是表單機器。** 證據弱的時候要開口跟人類要，不是默默記 `C/低`。

1. AI 問問題（含 §3-§6 的 probe 規則）
2. 人類回答「+0.7，因為 XX」
3. AI 內部判定證據類型（**A/B/C/D**）
4. AI 內部判定信心度（**高/中/低**）
5. **若信心度=低、或值極端但無具體場景、或人類說「不知道」**：
   - AI 從「提問庫」（§2.5.1）挑一題開口要更多證據
   - 人類補完
   - AI 重新判定證據類型 + 信心度（可能 A→B→C 升級，或值改）
6. AI 反推**邊界**：「為什麼不是 +0.5 或 +0.9？」
7. AI 寫成 5-field 結構
8. 進入下一題

**任何一欄缺漏都算「值不完整」**，AI 應在收尾時把所有不完整的列出來請人類補。

### 2.5.1 主動提問庫（AI 看到證據弱就開口）

| 觸發條件 | 提問模板 | 目的 |
|---|---|---|
| 信心度=低（沒場景） | 「你想得起她哪個具體行為支持這判斷嗎？場景 X、Y、Z 任一」 | 從 C 升到 B |
| 值極端但無場景 | 「X 已經偏強。給我 2-3 個原作/設定中她明顯表現 X 的場景」 | 確認 A 證據 |
| 給中性值「不知道」 | 「如果想像她遇到 Y 極端情境，她大概會怎樣？」 | 從 C 升到 B 或逼出值 |
| 兩個值看起來矛盾 | 「你說 X 高但 Y 低，這通常不太常見。是什麼原因讓她這樣？」 | 找 complex_appraisal 解釋 |
| 提到特定對象 | 「你說她『對段譽』特別 X，這是只對他、還是她對內團體都 X？」 | 判斷全域 vs complex |
| 邊界推論模糊 | 「你給 X。為什麼不是 X+0.3？什麼場景會讓她 X 更強？」 | 確認值不是亂填 |
| 應用情境問題 | 「這個 chatbot / 遊戲 預期平均對話多長？」 | 為 decay 取證據 |
| 真人角色 | 「這是公開資料的角色？私領域的我不會接」 | 守界線 |
| 一個值收很久無法定 | 「OK 我們先跳過這個，標 C/低，最後一輪再回頭補」 | 避免卡死 |

**AI 不要害怕開口要**。人類在場就是最大的證據來源 — 用他比用你自己推論有效。

### 2.5.2 提問時的語氣

- 不要咄咄逼人：「**你必須給我場景**」❌
- 用「我想確認」/「我想 capture 更準」/「這個值在 production 之前我會希望你 sign off」✅
- 把人類當 co-author，不是 form-filler

範例：
```
❌ 「你給 0.0 但沒給場景。請給我至少一個原作情節支持這個判斷。」
✅ 「Openness 收到 0.0。這個值有點敏感 —
    你能想到她哪個原作行為（即使是側面）支持這個判斷？
    像是她遇到新事物時的第一反應、她對段譽的接受過程等。
    沒有的話我們標 C/低，最後一輪再回頭。」
```

**人類回答「我想不到」是合法答案**：那就標 `C/低/信心度低`、rationale 寫「人類無法提供場景」，交給 §6 整體驗收時由人類決定要不要降為中性值。

---

## 3. Big Five 全域人格（5-10 輪）

> **每個值都套用 §2.5 證據協議**：收到數值 + 證據類型 + 信心度 + 邊界推論才算「這個 trait 收完」。

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

> 證據弱時**主動開口要**，不要默默標 C/低。具體提問模板見 §2.5.1。

| 人類回答 | AI 動作 |
|---|---|
| 極端值（>0.7 或 <-0.7） | 要求 2-3 個具體場景 |
| 中性值（接近 0） | 接受，標記「無強烈傾向」 |
| 「看情況」 | 追問「在什麼場景傾向哪邊」 |
| 兩 trait 矛盾 | 點出來（例：「你說 C=0.8 但他常遲到耶？」） |
| 提到特定對象的反應 | **標記進 complex_appraisal**，不污染全域 |
| 「不知道」 | 給兩個情境讓他選更像哪個 |
| 答很快沒解釋 | 用 §2.5.1 「邊界推論」模板追問 |
| **給場景後 AI 判斷證據仍弱** | 再用 §2.5.1 模板開口要第 2 輪證據，或決定降中性 |

5 個收完時主動讀回確認：
```
全域人格：O=0.0, C=0.6, E=-0.6, A=-0.4, N=0.3。
守規、內向、不輕信人情、中等情緒穩定。
確認？要調哪個？
```

---

## 4. 18 個 Appraisal Rules（10-20 輪）

> **每個值都套用 §2.5 證據協議**。18 個 tag × 證據 = 18 個 5-field 結構要寫。

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

> **每條都套用 §2.5 證據協議**。每條 complex_appraisal 都要有 5-field 結構：值（kind/signal/performer/appraisal 物件）+ 證據類型 + 信心度 + 邊界。

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

> **每個值都套用 §2.5 證據協議**。decay 參數雖然不像 Big Five 那樣有「原作情節」可引，但仍有「為什麼這樣設」的依據（應用節奏、玩家平均對話長度等）。證據類型常用 `D`（企劃調參）。

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

### 7.4 角色訪談結果.md（必出，給人類 review 用）

> 這是「**定稿**」：最終決定的數值 + 為什麼。光給 JSON 不夠 — 人類需要看到「為什麼是這些值」才能在 production 之前把關。

AI 必須**同步輸出**這份 markdown，採用「**木婉清示範**」的格式。

#### 7.4.1 檔名

`角色訪談結果.md`（同目錄放 JSON 旁邊）。同角色多版本時加版本號：`角色訪談結果_v2.md`。

#### 7.4.2 結構

```markdown
# <角色名> 角色訪談結果

> 對應 JSON：`<角色名>.json`
> 對應訪談過程：`角色訪談過程全紀錄.md`
> 生成時間：<ISO date>
> AI 引導員：<model + 對話 session id>
> 訪談輪數：<N 輪>
> probe 次數：<M 次>（其中 X 次值被調整）

## 0. 角色核心假設（驗收基準）

> 一段話描述這個角色**最關鍵的人格剖面**。後面每個值都要能回到這段。

## 1. 識別

### I01. 角色名稱
- 填入: `<name>`
- 證據類型: D
- 信心度: 高
- 為何這樣填: ...
- 為何不是其他: ...

### I02. 角色簡述
- 填入: `<簡述>`
- ...

## 2. Big Five

### O. 開放性
- 填入數值: <v>
- 證據類型: A | B | C | D
- 信心度: 高 | 中 | 低
- 為何這樣填: ...
- 為何不是更高或更低: ...
- 對模型的意義: ...

### C. 盡責性
（同上格式）

### E / A / N
（同上格式）

## 3. 18 個 Appraisal Rules

### GoodEvent
（同 5-field 格式 + 對模型的意義）

### GoodEventForGoodOther
...

（18 個 tag 全部列）

## 4. Complex Appraisal

### Entry 1: kind=self_emotion, signal=Joy, performer=木婉清
（同 5-field 格式 + 對模型的意義）

## 5. Decay & Simulation

### mood.decay_time
- 填入: 600000
- 證據類型: D（企劃調參）
- 信心度: 中
- 為何這樣填: chatbot 對話預期 5-10 分鐘，decay 10 分鐘讓強烈事件留下痕跡
- 為何不是其他: 30 秒太短，玩家的情緒輸入來不及反饋；30 分鐘太長，跨對話殘留
- 對模型的意義: ...

（6 個值全部列）

## 6. 整體驗收

> 對照 §0 角色核心假設，檢查這個 JSON 跑起來會不會：
> - 對陌生人過度反應？（agreeableness 太低）
> - 對段譽/特定對象沒有顯著差異？（complex_appraisal 沒建好）
> - 情緒殘留太久/太短？（decay 設錯）
> 
> 不對的地方列出，請人類決定要不要調。
```

#### 7.4.3 為什麼這份重要

| 用途 | 說明 |
|---|---|
| **Review 用** | 開發者 / 企劃在上 production 前把關，避免「數值飄」的角色 |
| **回頭調用** | 玩家反應「這個角色某個反應不對」時，能回到這份找哪個值要改 |
| **角色演進記錄** | 跨版本（v1.0 → v1.1）的角色調整對照表 |
| **AI 引導員品質審核** | 哪個 AI session 容易亂填值、容易漏邊界推論 |

#### 7.4.4 跟 JSON 跟 全紀錄 的對應關係

- **JSON 餵進 REST**：`POST /characters` 接受
- **結果.md 人 review**：人類讀這份做品管
- **全紀錄.md 人 audit**：當結果裡某個值被質疑，回頭看全紀錄確認當時 AI 有沒有 probe、有沒有充分取證
- **欄位一一對應**：JSON 的每個 leaf value 都在結果.md 5-field 結構、結果.md 的每個值都能在全紀錄.md 找到當時的對話出處
- **若結果跟全紀錄對不上**：表示 AI 自己寫得不清楚，**必須重做**

---

### 7.5 角色訪談過程全紀錄.md（必出，給反覆探討用）

> 這是「**依據**」：每一輪 Q&A、AI 內部判定、probe 觸發、值變動的完整時間軸。
> 
> **存在目的**：讓人類（或另一個 reviewer）能**反覆回到訪談當下**，檢視每個數值決定當時 AI 是怎麼問、人類是怎麼答、證據是怎麼被蒐集的。當結果.md 的某個值後來被質疑，這份是查證的唯一來源。

#### 7.5.1 檔名

`角色訪談過程全紀錄.md`（跟結果.md 放同目錄）。

#### 7.5.2 結構

```markdown
# <角色名> 角色訪談過程全紀錄

> 對應結果：`角色訪談結果.md`
> 對應 JSON：`<角色名>.json`
> 開始時間：<ISO datetime>
> 結束時間：<ISO datetime>
> 總輪數：<N>
> probe 觸發次數：<M>
> AI 調整值次數：<K>
> 人類放棄給證據次數：<L>

---

## 輪次 1（開場）
- AI: <問題>
- 人類: <回答>
- AI 內部: <判定 / 5-field 結構>

## 輪次 2
- AI: <問題>
- 人類: <回答>
- AI 內部: ...
- 是否觸發 probe: 否
- 最終值: ...

## 輪次 3（probe 觸發）
- AI: <問題>
- 人類: <回答>
- AI 內部判定: 證據類型=C, 信心度=低
- ⚠️ 觸發 probe（§2.5.1: 信心度低 / 沒場景）
- AI probe: 「<追問>」
- 人類補: <回答>
- AI 重新判定: 證據類型=B, 信心度=中
- 最終值: <v>

## 輪次 4（值被調整）
- AI: <問題>
- 人類: <回答 1>
- AI 內部判定: 證據類型=B, 信心度=中, 填入=X
- ⚠️ probe 觸發後值改變
- AI probe: 「<追問>」
- 人類: <回答 2>
- AI 重新判定: 填入=Y（從 X 改成 Y）
- 為何改: ...

## 輪次 5（人類放棄給證據）
- AI: <問題>
- 人類: <回答>
- AI 內部判定: 證據類型=C, 信心度=低
- ⚠️ 觸發 probe
- AI probe: 「<追問>」
- 人類: 「我想不到」
- AI: 接受 C/低，標記「待證實」
- 最終值: <v>，證據 C/低，rationale 註明人類無法提供

---

## 摘要（給快速 review 用）

### 證據強度分布

| 階段 | A (高支持) | B (推論) | C (證據不足) | D (企劃) |
|---|---|---|---|---|
| Big Five | 0 | 4 | 1 | 0 |
| Appraisal | 2 | 12 | 4 | 0 |
| Complex | 0 | 1 | 0 | 0 |
| Decay | 0 | 0 | 0 | 6 |

### 改值事件（K 個）

| 輪次 | 原值 | 新值 | 觸發原因 |
|---|---|---|---|
| 4 | 0.0 | -0.3 | probe 找到反例 |

### 卡住事件（L 個）

| 輪次 | 哪個欄位 | 人類放棄原因 | 標記 |
|---|---|---|---|
| 5 | Agreeableness | 沒有明確反例 | 待證實 |

### Probe 觸發事件（M 個）

| 輪次 | 觸發條件 | probe 內容 | 結果 |
|---|---|---|---|
| 3 | 信心度=低 | 「場景 X？」 | 升 B/中 |
| 4 | 值極端無場景 | 「2-3 個原作情節？」 | 改值 |

---

## 自我審核（AI 在訪談結束時填）

- [ ] 每個值都套用 §2.5 證據協議
- [ ] 證據弱時有主動開口要
- [ ] 值變動都有 probe 觸發 + 記錄
- [ ] 人類放棄的部分都有標記
- [ ] 結果.md 跟全紀錄.md 對得上
```

#### 7.5.3 為什麼要分兩份

| 用途 | 結果.md | 全紀錄.md |
|---|---|---|
| 給 production reviewer 看 | ✓ | ✗ 太長 |
| 給 AI 訓練 / 改進看 | ✗ 缺細節 | ✓ |
| 給「這個值為什麼是 X」追溯 | ✗ 只有結論 | ✓ 有過程 |
| 給跨版本比對（v1 vs v2） | ✓ diff 結果 | ✓ diff 過程 |
| 給「probe 該不該更積極」檢討 | ✗ | ✓ 顯式列出每個 probe |

#### 7.5.4 全紀錄的使用情境

- **情境 A — Reviewer 質疑某個值**：「為什麼木婉清 Openness 是 0.0 不是 -0.3？」→ 開全紀錄找 Openness 那輪，看當時問了什麼、人類答什麼、probe 觸發了沒有
- **情境 B — 改版本**：「我們要把木婉清 Openness 改成 -0.3，需要重訪談嗎？」→ 看全紀錄的「改值事件」跟「卡住事件」，評估是否需要新一輪訪談
- **情境 C — AI 改進**：「這個 AI 引導員對段譽問題處理得好嗎？」→ 看所有「提到特定對象」的輪次，比對不同 AI session 的 probe 表現
- **情境 D — 同角色不同 AI 比對**：「AI A 給 0.0、AI B 給 -0.3，哪個對？」→ 並排看兩份全紀錄的 probe 深度跟證據量

#### 7.5.5 AI 寫全紀錄的紀律

- **每一輪都要寫**，不可省略「看起來無聊」的輪次
- **probe 觸發必須顯式標記**（用 `⚠️` emoji 或 `**[probe]**` 開頭）
- **值變動必須記「為何改」**，不能只記前後值
- **人類放棄時不能裝沒事**，要寫「人類放棄給證據，AI 接受 C/低」
- **AI 內部 5-field 結構要寫出來**，不只是值
- **時間戳記要精確到秒**，跨日才能 replay

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
