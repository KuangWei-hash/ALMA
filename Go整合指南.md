# Go 專案整合 ALMA — 從零開始指南

> 你的角色：Go 專案開發者，想用 ALMA 做角色的情感/心情/情緒計算。
> ALMA 是 Java jar，透過 REST wrapper（`AlmaRestServer`）暴露 HTTP API，Go 端就純 HTTP client。

---

## 全流程總覽

```
一次性準備 (只做一次)
├─ 1. 環境確認 (JDK 11 + Ant)
├─ 2. 編譯 ALMA (產出 lib/affect.jar，內含 REST server)
└─ 3. 設定角色 (Big Five + Appraisal 規則)

每次要用 ALMA 時
└─ 4. 啟動 REST server (./run.sh rest)

Go 專案整合
├─ 5. 定義 HTTP client
├─ 6. 送事件 (POST /event)
└─ 7. 讀狀態 (GET /affect/{name})
```

---

## Step 1: 環境確認

需要：
- **JDK 11**（`/usr/lib/jvm/java-11-openjdk-amd64` 或類似路徑）
- **Ant**（`/usr/bin/ant`）
- **curl**（測試用）

WSL / Ubuntu：
```bash
sudo apt-get install -y openjdk-11-jdk ant curl
```

驗證：
```bash
javac -version    # javac 11.x.x
ant -version      # Apache Ant(TM) version 1.10.x
```

---

## Step 2: 編譯 ALMA

```bash
cd /mnt/d/ALMA
./run.sh build
```

成功後應該看到 `BUILD SUCCESSFUL`，產物在 `lib/affect.jar`。

驗證 REST server class 有進 jar：
```bash
jar tf lib/affect.jar | grep rest
# → de/affect/rest/AlmaRestServer.class
```

---

## Step 3: 設定角色

### 3.1 核心觀念

ALMA 一次載入**兩份設定檔**：

| 檔案 | 內容 | 一般會不會改 |
|---|---|---|
| `conf/AffectComputation*.aml` | **全域計算參數**：允許哪些 OCC 情緒、Big Five→PAD 權重（Mehrabian 實驗值）、輸出頻率 | 通常沿用 Example 不改 |
| `conf/AffectDefinition*.aml` | **每個角色**：Big Five 值、Mood/Emotion decay、Appraisal 規則 | **這裡是你設計的重點** |

### 3.2 最快開始：直接用範例

`conf/AffectDefinitionExample.aml` 已有兩個現成角色：

- **Anne**：極外向 + 極親和 + 神經質偏高（`E=+1.0, A=+0.8, N=+0.3`）
- **Bob**：全 0 中性人格

不改任何檔案，`./run.sh rest` 就能開跑。

### 3.3 自己定義角色

複製 example 檔改一份：
```bash
cp conf/AffectDefinitionExample.aml conf/MyCharacters.aml
```

編輯 `conf/MyCharacters.aml`，每個角色最少要有這四段：

```xml
<CharacterAffect name="Villain_01" docu="陰沉的敵人" monitored="false">

  <!-- 1. Big Five 人格（值域 [-1, +1]） -->
  <PersonalitySpecification derived="false" emotioninfluence="0.20"
    openness="-0.5"          <!-- 保守、務實 -->
    conscientiousness="0.3"  <!-- 稍微有計劃 -->
    extraversion="-0.8"      <!-- 極內向 -->
    agreeableness="-0.5"     <!-- 不合作、多疑 -->
    neurotism="0.7"/>        <!-- 情緒起伏大、易怒 -->

  <!-- 2. Mood 衰減：10 分鐘回歸 default，每 250ms 更新 -->
  <MoodSpecification decaytime="600000" decayperiod="250"
    neurotismstability="false"/>

  <!-- 3. Emotion 衰減：20 秒衰減 -->
  <EmotionSpecification decaytime="20000" decayperiod="500"
    decayfunction="linear" baseline="0.5"/>

  <!-- 4. Appraisal 規則：對 18 種 tag 的預設評估 -->
  <Appraisal><Basic>
    <GoodEvent desirability="0.3"/>              <!-- 對他來說好事效果較弱 -->
    <BadEvent desirability="-0.8"/>              <!-- 壞事反應大 -->
    <GoodActOther praiseworthiness="0.2" agency="other"/>
    <BadActOther praiseworthiness="-0.9" agency="other"/>
    <NiceThing appealingness="0.3"/>
    <NastyThing appealingness="-0.8"/>
    <!-- 沒列的 tag 會用系統 default -->
  </Basic></Appraisal>

</CharacterAffect>
```

啟動時指定你的檔案：
```bash
java -cp lib/affect.jar:lib/*:lib/processing/* \
  de.affect.rest.AlmaRestServer \
  --comp conf/AffectComputationExample.aml \
  --def  conf/MyCharacters.aml \
  --port 8080
```

或改 `bin/build.xml` 的 `run-rest` target 換掉 `--def`。

---

## Step 4: 啟動 REST server

```bash
cd /mnt/d/ALMA
./run.sh rest
```

輸出應該長這樣：
```
[alma-rest] loading comp=conf/AffectComputationExample.aml def=conf/AffectDefinitionExample.aml
[alma-rest] listening on http://localhost:8080
[alma-rest] endpoints:
  GET  /health
  GET  /characters
  GET  /affect
  GET  /affect/{name}
  POST /event      {character, tag, intensity?, elicitor?}
  POST /pad        {character, p, a, d, intensity?, elicitor?}
  POST /pause?character={name}
  POST /resume?character={name}
```

**重要提醒 — server 是有狀態的**：
- Mood/Emotion 有累積歷程（emotion decay、mood 漂移都依賴時序）
- 你的 Go 服務應該跟這個 server **長期連線**（不要每個 request 都重啟 server）
- Server 掛掉重啟 → 所有 mood/emotion 歷程消失，personality 從 conf 重新載入

**生產環境**：
- 用 systemd / supervisor 開機自啟
- Log 導檔：`./run.sh rest > /var/log/alma.log 2>&1`
- 定期監控 `GET /health`

---

## Step 5: Go 端 HTTP Client

### 5.1 最小可用 Client

```go
package alma

import (
    "bytes"
    "encoding/json"
    "fmt"
    "net/http"
    "time"
)

type Client struct {
    BaseURL string
    HTTP    *http.Client
}

func NewClient(baseURL string) *Client {
    return &Client{
        BaseURL: baseURL,
        HTTP:    &http.Client{Timeout: 5 * time.Second},
    }
}

// Affect 是 GET /affect/{name} 的回傳型別
type Affect struct {
    Name            string         `json:"name"`
    DominantEmotion EmotionRecord  `json:"dominant_emotion"`
    Mood            MoodRecord     `json:"mood"`
    Emotions        []EmotionRecord `json:"emotions"`
}

type EmotionRecord struct {
    Name      string  `json:"name"`       // Joy / Distress / ...
    Intensity float64 `json:"intensity"`  // 0.0 ~ 1.0
}

type MoodRecord struct {
    Word      string `json:"word"`       // Exuberant / Relaxed / Anxious / ...
    Intensity string `json:"intensity"`  // neutral / slightly / moderate / fully
    Tendency  string `json:"tendency"`   // 目前 mood 傾向哪個 word
}
```

### 5.2 三個核心方法

```go
// SendEvent 送 OCC 事件（Event / Action / Object tag）
func (c *Client) SendEvent(character, tag, intensity, elicitor string) error {
    body, _ := json.Marshal(map[string]string{
        "character": character,
        "tag":       tag,
        "intensity": intensity,
        "elicitor":  elicitor,
    })
    return c.post("/event", body)
}

// SendPAD 直接注入 PAD 值（跳過 appraisal）
func (c *Client) SendPAD(character, p, a, d, intensity, elicitor string) error {
    body, _ := json.Marshal(map[string]string{
        "character": character,
        "p":         p, "a": a, "d": d,
        "intensity": intensity,
        "elicitor":  elicitor,
    })
    return c.post("/pad", body)
}

// GetAffect 讀取角色目前 affect state
func (c *Client) GetAffect(character string) (*Affect, error) {
    resp, err := c.HTTP.Get(c.BaseURL + "/affect/" + character)
    if err != nil { return nil, err }
    defer resp.Body.Close()
    if resp.StatusCode != 200 {
        return nil, fmt.Errorf("alma /affect/%s → %d", character, resp.StatusCode)
    }
    var a Affect
    if err := json.NewDecoder(resp.Body).Decode(&a); err != nil {
        return nil, err
    }
    return &a, nil
}

func (c *Client) post(path string, body []byte) error {
    resp, err := c.HTTP.Post(c.BaseURL+path, "application/json", bytes.NewReader(body))
    if err != nil { return err }
    defer resp.Body.Close()
    if resp.StatusCode != 200 {
        return fmt.Errorf("alma %s → %d", path, resp.StatusCode)
    }
    return nil
}
```

### 5.3 完整跑一次

```go
package main

import (
    "fmt"
    "log"
    "time"
    "your/project/alma"
)

func main() {
    c := alma.NewClient("http://localhost:8080")

    // 送個好事給 Bob
    if err := c.SendEvent("Bob", "GoodEvent", "0.9", "player gave item"); err != nil {
        log.Fatal(err)
    }

    // 等 mood 更新（server 每 500ms 更新一次）
    time.Sleep(600 * time.Millisecond)

    // 讀狀態
    a, err := c.GetAffect("Bob")
    if err != nil { log.Fatal(err) }

    fmt.Printf("Bob: %s (%.2f), mood=%s %s\n",
        a.DominantEmotion.Name, a.DominantEmotion.Intensity,
        a.Mood.Intensity, a.Mood.Word)
    // 預期輸出: Bob: Joy (0.40), mood=slightly Exuberant
}
```

---

## Step 6: 三種常見使用模式

### Pattern A：事件觸發式（最典型）

玩家/系統做了什麼 → 通知 ALMA → 讀 mood 決定 NPC 反應。

```go
func OnPlayerAction(npcName, action string) {
    var tag, intensity string
    switch action {
    case "give_gift":  tag, intensity = "NiceThing",     "0.7"
    case "attack":     tag, intensity = "BadActOther",   "0.9"
    case "help":       tag, intensity = "GoodActOther",  "0.8"
    case "insult":     tag, intensity = "BadActOther",   "0.6"
    default: return
    }

    if err := almaClient.SendEvent(npcName, tag, intensity, "player:"+action); err != nil {
        log.Printf("alma error: %v", err)
        return
    }

    time.Sleep(600 * time.Millisecond)
    a, err := almaClient.GetAffect(npcName)
    if err != nil { return }

    NPCReactBasedOnMood(npcName, a.Mood.Word, a.DominantEmotion.Name)
}
```

### Pattern B：定期 poll 決定台詞

```go
// 每秒 poll，選符合當前 mood 的台詞
ticker := time.NewTicker(1 * time.Second)
defer ticker.Stop()

for range ticker.C {
    a, err := almaClient.GetAffect("Anne")
    if err != nil { continue }

    // 依 mood word + intensity 挑不同語氣的台詞
    line := PickDialogLine(a.Mood.Word, a.Mood.Intensity)
    ShowLine("Anne", line)
}

// PickDialogLine 的表：
// (Anxious, moderate) → "你...你確定要這麼做嗎？"
// (Exuberant, fully) → "哈！沒問題交給我！"
// (Bored, slightly)  → "嗯，好啊。"
```

### Pattern C：直接 PAD 注入（跳過 appraisal）

用於劇情事件直接指定情感，不走評估邏輯：

```go
// 恐怖場景：直接把 Anne 設成焦慮
almaClient.SendPAD("Anne", "-0.6", "0.5", "-0.7", "1.0", "horror scene")

// 結尾場景：把 Bob 設成放鬆
almaClient.SendPAD("Bob", "0.7", "-0.5", "0.4", "1.0", "ending scene")
```

---

## Step 7: 生產環境考量

### 錯誤處理

```go
// 帶 retry 的呼叫
func SendEventWithRetry(c *alma.Client, name, tag, intensity, elicitor string) error {
    var lastErr error
    for i := 0; i < 3; i++ {
        if err := c.SendEvent(name, tag, intensity, elicitor); err == nil {
            return nil
        } else {
            lastErr = err
            time.Sleep(time.Duration(i+1) * 100 * time.Millisecond)
        }
    }
    return lastErr
}
```

### Circuit breaker

當 ALMA 掛掉時降級回 default 反應：

```go
func GetMoodOrDefault(c *alma.Client, name string) string {
    a, err := c.GetAffect(name)
    if err != nil {
        log.Printf("alma unreachable, using default mood: %v", err)
        return "Neutral"
    }
    return a.Mood.Word
}
```

### 監控

```go
// 每 30 秒 ping /health
go func() {
    for range time.Tick(30 * time.Second) {
        resp, err := http.Get("http://localhost:8080/health")
        if err != nil || resp.StatusCode != 200 {
            metrics.AlmaDown.Inc()
        }
    }
}()
```

---

## 附錄 A：18 種 OCC Tag 完整對照

送 `POST /event` 時的 `tag` 值：

### Event tags（12 種）

| Tag | 語意 | 主要觸發 |
|---|---|---|
| `GoodEvent` | 好事發生 | Joy |
| `BadEvent` | 壞事發生 | Distress |
| `GoodEventForGoodOther` | 好事發生在喜歡的人身上 | HappyFor |
| `GoodEventForBadOther` | 好事發生在討厭的人身上 | Resentment |
| `BadEventForBadOther` | 壞事發生在討厭的人身上 | Gloating |
| `BadEventForGoodOther` | 壞事發生在喜歡的人身上 | Pity |
| `GoodLikelyFutureEvent` | 好事可能發生 | Hope |
| `BadLikelyFutureEvent` | 壞事可能發生 | Fear |
| `GoodUnlikelyFutureEvent` | 好事可能發生（低機率） | 弱 Hope |
| `BadUnlikelyFutureEvent` | 壞事可能發生（低機率） | 弱 Fear |
| `EventConfirmed` | 預期事件確認發生 | Satisfaction / FearsConfirmed |
| `EventDisconfirmed` | 預期事件確認未發生 | Relief / Disappointment |

### Action tags（4 種）

| Tag | 語意 | 主要觸發 |
|---|---|---|
| `GoodActSelf` | 自己做了好事 | Pride |
| `BadActSelf` | 自己做了壞事 | Shame |
| `GoodActOther` | 別人做了好事 | Admiration |
| `BadActOther` | 別人做了壞事 | Reproach |

### Object tags（2 種）

| Tag | 語意 | 主要觸發 |
|---|---|---|
| `NiceThing` | 遇到讓人喜歡的東西 | Liking |
| `NastyThing` | 遇到讓人討厭的東西 | Disliking |

---

## 附錄 B：Mood Word 完整對照（8 + Neutral）

| P | A | D | Mood Word | 中文 | 建議 NPC 表現 |
|---|---|---|---|---|---|
| + | + | + | Exuberant | 亢奮 | 熱情、活力、笑聲 |
| + | + | − | Dependent | 依附 | 撒嬌、依賴、需要陪伴 |
| + | − | + | Relaxed | 放鬆 | 悠閒、慢語調、微笑 |
| + | − | − | Docile | 溫馴 | 順從、安靜、聽話 |
| − | + | + | Hostile | 敵對 | 挑釁、逼近、大聲 |
| − | + | − | Anxious | 焦慮 | 顫抖、避開、快語調 |
| − | − | + | Disdainful | 輕蔑 | 冷淡、看不起、拒絕 |
| − | − | − | Bored | 無聊 | 打哈欠、無反應、視線飄 |
| 0 | 0 | 0 | Neutral | 中性 | 平淡、無特殊反應 |

Mood intensity 四檔：`neutral / slightly / moderate / fully`

---

## 附錄 C：常見問題

### Q1: 為什麼送完 event 立刻 GET /affect 沒變化？
**A**：ALMA 內部每 500ms 才 broadcast 一次 update（在 `AffectComputation.aml` 的 `<RealtimeOutput period="500">` 設定）。送完 event 至少等 500~600ms 再讀。

### Q2: 一個 ALMA server 能承載多少角色？
**A**：主要限制是記憶體 + update loop 時間。~50 個角色沒問題，過百角色可能要開多個 server 分片。

### Q3: Server crash 了怎麼辦？
**A**：重啟後 mood/emotion 歷程全丟，personality 從 conf 檔重新載入。目前 ALMA 不支援 state persistence，如果需要，得自己在 Go 端記錄 mood 快照 + 重啟後用 `POST /pad` 手動還原。

### Q4: 需要什麼樣的 Big Five 值？
**A**：沒有標準答案。建議 iteration：
1. 先用 Bob（全 0）當 baseline 對照組
2. 一次只改一個 trait，觀察反應差異
3. 對照 `docs/EmotionPADMappings.txt` 理解每種情緒的 PAD 座標

### Q5: appraisal rule 不改可以嗎？
**A**：可以，沒列的 tag 用系統 default 值。**但**「同一個 event 不同角色反應不同」的效果就沒有 — 因為所有角色的 desirability 都會是同樣的預設值。要做出角色差異，就得改 appraisal rule。

### Q6: 想要更快的更新頻率怎麼辦？
**A**：改 `conf/AffectComputationExample.aml` 的 `<RealtimeOutput period="500">`，改成 100（100ms）。太快會拖 CPU，太慢反應遲鈍。

### Q7: Go 端能不能 subscribe 用 WebSocket / SSE，不用 poll？
**A**：目前 REST wrapper 只支援 poll。要 push 得改 `AlmaRestServer.java` 加 WebSocket handler（JDK 內建 `com.sun.net.httpserver` 不支援，得換 Undertow 之類）。如果真的需要，跟我說可以補。

### Q8: PAD tendency 是什麼？跟 mood 差在哪？
**A**：`mood.word` 是**當下**座標所在象限；`mood.tendency` 是**近期漂移方向**。例如 mood 目前在 Neutral 邊界但正在往 Exuberant 移動，word=Neutral, tendency=Exuberant。

---

## 快速開始 Checklist

- [ ] JDK 11 + Ant 裝好
- [ ] ALMA 專案在 `/mnt/d/ALMA`
- [ ] `./run.sh build` 產出 `lib/affect.jar` 內含 `de/affect/rest/AlmaRestServer.class`
- [ ] `./run.sh rest` 啟動，`curl http://localhost:8080/health` 回 `{"status":"ok",...}`
- [ ] `curl http://localhost:8080/characters` 看到 `["Anne","Bob"]`
- [ ] Go 端 `SendEvent("Bob", "GoodEvent", "0.9", "test")` → `GetAffect("Bob")` 拿到 dominant emotion = Joy
- [ ] 決定要沿用 example 角色還是複製一份 `AffectDefinition*.aml` 改
- [ ] 決定生產部署方式（systemd / supervisor / docker）

---

## 相關文件

- `ALMA_專案說明.md` — ALMA 專案整體架構、程式碼組織、code path 追蹤
- `心理學詞彙解釋說明.md` — 用到的心理學術語詳解（OCC / PAD / Big Five）
- `bin/build.xml` — Ant build 檔（`run-rest` target）
- `src/de/affect/rest/AlmaRestServer.java` — REST wrapper 原始碼
