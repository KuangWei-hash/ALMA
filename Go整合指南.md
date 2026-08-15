# Go 專案整合 ALMA

本指南只說明 Go 呼叫端。所有 JSON 欄位、18 種 tag、EEC 有效組合、Complex Appraisal 與動態角色完整範例，請以 `使用教學.md` 為準。

## 1. 準備與啟動

需求：JDK 11、Ant、curl。

```bash
sudo apt-get install -y openjdk-11-jdk ant curl
cd /mnt/d/ALMA
./build.sh
./run_rest.sh
```

指定設定與 port：

```bash
./run_rest.sh \
  --comp conf/AffectComputationExample.aml \
  --def conf/AffectDefinitionExample.aml \
  --port 8080
```

確認服務：

```bash
curl http://localhost:8080/health
curl http://localhost:8080/characters
```

REST 模式不開 GUI。服務有狀態：emotion decay 與 mood 漂移會持續運算；程序重啟後，執行期建立的角色、群組與 affect 歷程都會消失。

## 2. 應用程式應使用的輸入層級

| Endpoint | 適用情境 | 原生 ALMA 輸入 |
|---|---|---|
| `POST /appraisal` | 使用角色事先定義的 18 種 Basic tag | Event、Action 或 Object |
| `POST /event` | `/appraisal` 的舊相容別名 | 同上 |
| `POST /eec` | 外部系統已自行完成 appraisal | BasicEEC |
| `POST /act` | 讓角色依 self/direct/indirect 規則解讀行為 | Act |
| `POST /emotion-display` | 角色表達某種 emotion | EmotionDisplay |
| `POST /mood-display` | 角色表達某種 mood | MoodDisplay |
| `POST /pad` | biosensor／外部 PAD affect 輸入 | Physical emotion |

`/pad` 不會直接指定 mood。它建立 `Physical` emotion，再由 ALMA 的 emotion-to-mood 動態逐步影響 mood，因此也不能拿來還原 mood snapshot。

## 3. 型別安全的最小 Go Client

```go
package alma

import (
    "bytes"
    "context"
    "encoding/json"
    "fmt"
    "io"
    "net/http"
    "net/url"
    "time"
)

type Client struct {
    BaseURL string
    HTTP    *http.Client
}

func NewClient(baseURL string) *Client {
    return &Client{
        BaseURL: baseURL,
        HTTP: &http.Client{Timeout: 5 * time.Second},
    }
}

type AppraisalInput struct {
    Character string  `json:"character"`
    Tag       string  `json:"tag"`
    Intensity float64 `json:"intensity"`
    Elicitor  string  `json:"elicitor"`
}

type PADInput struct {
    Character   string  `json:"character"`
    Pleasure    float64 `json:"pleasure"`
    Arousal     float64 `json:"arousal"`
    Dominance   float64 `json:"dominance"`
    Intensity   float64 `json:"intensity"`
    Description string  `json:"description"`
}

type Emotion struct {
    Name       string          `json:"name"`
    Intensity  float64         `json:"intensity"`
    Baseline   float64         `json:"baseline"`
    Active     bool            `json:"active"`
    Elicitor   *string         `json:"elicitor"`
    ElicitedAt *int64          `json:"elicited_at"`
    PAD        *PADCoordinates `json:"pad"`
    Appraisal  *EmotionAppraisal `json:"appraisal"`
}

type EmotionAppraisal struct {
    Desirability    *float64 `json:"desirability"`
    Praiseworthiness *float64 `json:"praiseworthiness"`
    Appealingness   *float64 `json:"appealingness"`
    Likelihood      *float64 `json:"likelihood"`
    Realization     *bool    `json:"realization"`
    Liking          *float64 `json:"liking"`
    Agency          *string  `json:"agency"`
}

type Personality struct {
    Openness         float64 `json:"openness"`
    Conscientiousness float64 `json:"conscientiousness"`
    Extraversion     float64 `json:"extraversion"`
    Agreeableness    float64 `json:"agreeableness"`
    Neurotism        float64 `json:"neurotism"`
    Derived          bool    `json:"derived"`
    EmotionInfluence float64 `json:"emotion_influence"`
}

type PADCoordinates struct {
    Pleasure  float64 `json:"pleasure"`
    Arousal   float64 `json:"arousal"`
    Dominance float64 `json:"dominance"`
}

type Mood struct {
    Word      string  `json:"word"`
    Intensity string  `json:"intensity"`
    Pleasure  float64 `json:"pleasure"`
    Arousal   float64 `json:"arousal"`
    Dominance float64 `json:"dominance"`
}

type Affect struct {
    Name                    string      `json:"name"`
    AffectComputationPaused bool        `json:"affect_computation_paused"`
    Personality             Personality `json:"personality"`
    DominantEmotion         Emotion     `json:"dominant_emotion"`
    Mood                    Mood        `json:"mood"`
    MoodTendency            *Mood       `json:"mood_tendency"`
    DefaultMood             Mood        `json:"default_mood"`
    Emotions                []Emotion   `json:"emotions"`
}

func (c *Client) SendAppraisal(ctx context.Context, in AppraisalInput) error {
    return c.post(ctx, "/appraisal", in)
}

func (c *Client) SendPAD(ctx context.Context, in PADInput) error {
    return c.post(ctx, "/pad", in)
}

func (c *Client) Post(ctx context.Context, path string, value any) error {
    return c.post(ctx, path, value)
}

func (c *Client) GetAffect(ctx context.Context, character string) (*Affect, error) {
    req, err := http.NewRequestWithContext(ctx, http.MethodGet,
        c.BaseURL+"/affect/"+url.PathEscape(character), nil)
    if err != nil { return nil, err }
    resp, err := c.HTTP.Do(req)
    if err != nil { return nil, err }
    defer resp.Body.Close()
    if resp.StatusCode != http.StatusOK {
        b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
        return nil, fmt.Errorf("ALMA GET affect: HTTP %d: %s", resp.StatusCode, b)
    }
    var affect Affect
    if err := json.NewDecoder(resp.Body).Decode(&affect); err != nil { return nil, err }
    return &affect, nil
}

func (c *Client) post(ctx context.Context, path string, value any) error {
    body, err := json.Marshal(value)
    if err != nil { return err }
    req, err := http.NewRequestWithContext(ctx, http.MethodPost,
        c.BaseURL+path, bytes.NewReader(body))
    if err != nil { return err }
    req.Header.Set("Content-Type", "application/json")
    resp, err := c.HTTP.Do(req)
    if err != nil { return err }
    defer resp.Body.Close()
    if resp.StatusCode < 200 || resp.StatusCode >= 300 {
        b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
        return fmt.Errorf("ALMA POST %s: HTTP %d: %s", path, resp.StatusCode, b)
    }
    return nil
}
```

JSON 數值必須用 Go `float64` 傳送，不能用字串。`intensity` 是 `0.0～1.0`；PAD 與 EEC 數值是 `-1.0～1.0`。雖然原始 XSD 對部分訊號的 intensity/elicitor 有預設值，REST 採 fail-fast 契約並要求明確傳入；這不是 ALMA XSD 本身的必填宣告。

## 4. 一次完整呼叫

```go
ctx := context.Background()
c := alma.NewClient("http://localhost:8080")

err := c.SendAppraisal(ctx, alma.AppraisalInput{
    Character: "Bob",
    Tag:       "GoodEvent",
    Intensity: 0.9,
    Elicitor:  "gift-42",
})
if err != nil { log.Fatal(err) }

affect, err := c.GetAffect(ctx, "Bob")
if err != nil { log.Fatal(err) }
fmt.Printf("emotion=%s %.3f, mood=%s %s\n",
    affect.DominantEmotion.Name,
    affect.DominantEmotion.Intensity,
    affect.Mood.Intensity,
    affect.Mood.Word)
```

Appraisal 與 emotion 產生發生在 `POST` 的同步核心流程，成功後可以立即讀取 emotion，不需要固定 sleep 500 ms。Mood 推進與 emotion decay 是計時器驅動；若業務邏輯要看 mood 後續變化，才依自己的採樣頻率 poll。

## 5. 角色互動 JSON

Go 可直接定義對應 struct，或用 map。多人參與時使用陣列：

```go
act := map[string]any{
    "performer":  "Bob",
    "addressees": []string{"Alice"},
    "listeners":  []string{"Anne"},
    "type":       "Help",
    "intensity":  0.9,
    "elicitor":   "help-001",
}
err := c.Post(ctx, "/act", act)
```

`addressee`／`listener` 是選填單一字串；`addressees`／`listeners` 是選填字串陣列，同一欄不可同時傳單數與複數。所有角色必須已存在。訊號被接受不保證產生情緒：收訊角色仍須有相符的 Complex Appraisal 規則。

EmotionDisplay/MoodDisplay 不是設定 performer 的 current state，而是讓角色表達並 appraisal 的社交訊號。原始核心要求 performer 先有 matching `SelfEmotion`／`SelfMood`，才會繼續處理角色收件者；缺少時 REST 回 `422`。Observer 的 `indirect_emotion`／`indirect_mood` 規則同時適用 addressee 與 listener。

## 6. 狀態、持久化與採樣

- `GET /affect/{character}` 直接讀取目前 ALMA state，不依賴 RealtimeOutput broadcast period。
- `dominant_emotion.active` 可區分事件觸發情緒與僅存在於人格 baseline 的值。
- 一般 OCC emotion 的 `pad` 是 ALMA `EmotionRelation` 映射；Physical 才使用輸入座標。純 baseline 的 `elicitor`、`elicited_at` 與 `appraisal` 為 `null`。
- `mood_tendency` 是 ALMA 的 PAD 趨勢，不是單一字串。
- 低頻採樣通常足夠；頻率由應用需求決定，沒有必要跟 ALMA timer 完全一致。
- 動態角色與群組不持久化。呼叫端應保存建立用 JSON，重啟後重新 `POST /characters`、`POST /groups`。
- Affect 歷程沒有無損 restore API；不要用 `/pad` 假裝還原 mood。

## 7. 常見錯誤

- `400`：JSON 欄位、值域、tag、EEC 組合或 enum 不符合 ALMA/XSD。
- `404`：角色、群組或 exact endpoint 路徑不存在。
- `409`：動態角色或群組名稱已存在。
- `409`：也可能表示 paused character 不接受 PAD、entity 尚未 pause 就要求 `/step`、member signal 會命中 paused group，或 internal appraisal 令 group pause 無法被 adaptor 嚴格保證。
- `413`：JSON request body 超過 REST adaptor 的 1 MiB 上限。
- `422`：請求格式合法，但會落入原始核心的無效 display route 或已知不安全 Love/Hate compound 路徑，因此 adaptor 在進核心前拒絕。
- `500`：原始核心處理或 adaptor 內部失敗；應記錄 response body 與 server log。

`elicitor` 應使用穩定事件 ID。Prospective emotion 的 `GoodLikelyFutureEvent`／`BadLikelyFutureEvent` 與後續 `EventConfirmed`／`EventDisconfirmed` 必須沿用同一 elicitor，核心才能找到要確認或否認的 emotion。

原始 ALMA 3.0 的 Love/Hate 合成路徑有 null dereference。REST 會依實際數值（核心把 `0` 視為非負側）拒絕 `agency=other` 且同號的 `praiseworthiness+appealingness`，並預檢同 elicitor 的 Admiration+Liking／Reproach+Disliking；adaptor 不重寫 frozen core 的情緒推導。

不要把外部事件 ID 設成 `alma internal emotion appraisal` 或 `alma internal mood appraisal`；它們是 core internal timer 的保留 elicitor，REST 會回 `422`。

## 8. 暫停與單步

```go
err := c.Post(ctx, "/pause?character="+url.QueryEscape("Alice"), struct{}{})
err = c.Post(ctx, "/step?character="+url.QueryEscape("Alice"), struct{}{})
err = c.Post(ctx, "/resume?character="+url.QueryEscape("Alice"), struct{}{})
```

亦可使用 `?group=name`；省略 query 作用於所有角色與群組。Pause 只停 timer 驅動的 decay/mood，普通 character appraisal 仍可同步改 emotion；paused 時 `/pad` 會回 `409`。會命中 paused group indirect rule 的 member Act/Display 也會回 `409`，避免原始 group path 偷重啟 timer。全域 pause 的「新 entity 繼承 paused」latch 只有無 query 的全域 `/resume` 會清掉；targeted resume 不會改變這項全域政策。

控制 API 只接受無 query（全域）或一個 `character`／`group`；未知、重複、空值或壞掉的 URL encoding 回 `400`，額外 path segment 回 `404`。因此 client typo 不會被誤解成全域 pause/resume/step。

`/step` 會要求核心額外執行一個 decay pass 和一個 mood pass，完成後維持 paused。原始 `Timer.cancel()` 不會中止已進入 callback 的 tick，所以剛 pause 後的 step 是 tick-boundary best effort，不是嚴格 deterministic clock。

## 9. 上線檢查

- JDK 11 與 Ant 已安裝。
- `./build.sh` 顯示 `BUILD SUCCESSFUL`。
- `./run_rest.sh` 啟動後 `/health` 正常。
- 呼叫端使用 JSON number，不把數值編碼成字串。
- 建立角色時提供完整 18-tag appraisal；互動功能另提供 Complex Appraisal。
- 若開啟 `internal_affect_appraisal`，確認角色有對應 self emotion/mood rules，並了解其獨立 500 ms timer 不受 `/pause` 控制；adaptor 會拒絕危險的跨規則 Love/Hate 組合及無法安全 pause 的群組配置。啟動時 adaptor 會延後原始 simulation，等 groups 與空 rule container 就緒後再啟動，避開核心原有的 500 ms 初始化競態。
- 保存動態角色／群組建立 JSON，規劃 ALMA 程序重啟後重建。
- 設定 HTTP timeout、錯誤 response body 紀錄與 `/health` 監控。
