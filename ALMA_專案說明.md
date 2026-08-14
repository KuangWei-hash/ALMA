# ALMA — A Layered Model of Affect（分層情感模型）

> Java 情感計算引擎（affective computing engine），DFKI（德國人工智慧研究中心）Patrick Gebhard 開發，2004~2012（本 repo 為 2015 年 GitHub 版本）。
> 用途：驅動虛擬角色（embodied conversational agents，具身對話代理）的即時情感反應。

---

## 一頁看懂

- **輸入**：AML XML 描述「事件、動作、物件」— 例如「Bruno 弄丟了假期照片」
- **處理**：對每個角色做 appraisal（評估），依據角色 personality（人格）→ 產生 24 種 OCC 情緒之一 → 情緒累積推動 mood（心情）→ mood 慢慢衰減回 baseline（基線）
- **輸出**：每個角色的 `Personality (Big Five 五大人格) / Mood (PAD 心情詞) / DominantEmotion (OCC 主導情緒)`
- **執行方式**：GUI（圖形介面）探索工具 `AffectManager` 或 Library（函式庫）嵌入到自家程式
- **相依**：Java 8+（實測 JDK 11 OK）、Swing、Java3D、Processing、JOGL、jama、xmlbeans

---

## 目錄

1. [理論基礎：為什麼叫「分層」模型](#理論基礎)
2. [系統架構：程式碼怎麼組織](#系統架構)
3. [設定檔詳解：三種 .aml 的分工](#設定檔詳解)
4. [完整資料流：從 XML 到情緒輸出](#完整資料流)
5. [目錄結構](#目錄結構)
6. [使用方式](#使用方式)
7. [已知技術債與坑](#已知技術債與坑)
8. [學習路徑建議](#學習路徑建議)

---

## 理論基礎

ALMA 全名 **A Layered Model of Affect（分層情感模型）**，「分層」是核心概念 — 情感有三個時間尺度（time scale），分別對應三個心理學模型。

### 三層模型

| 層 | 時間尺度 | 用什麼模型 | 特性 |
|---|---|---|---|
| **Emotion（情緒）** | 秒~分鐘（短期） | OCC 24 種情緒 | 由具體事件觸發，有 decay（衰減）曲線 |
| **Mood（心情）** | 分鐘~小時（中期） | PAD 三維空間 | 不綁事件，情緒累積推動；無事件時緩慢回歸 default（預設值） |
| **Personality（人格）** | 一生（長期） | Big Five（OCEAN，五大人格） | 個體差異，影響 default mood 與 emotion baseline（基線） |

**三層互動**：

```
Personality（人格，常數）
   ↓ 決定 default mood + emotion baseline
Mood（心情，漂移）
   ↑↓ 情緒累積推動 mood；無情緒時 mood 回 default
Emotion（情緒，瞬時）
   ↑ 由 appraisal（評估）觸發，會 decay（衰減）
```

`src/de/affect/compute/EmotionEngine.java:199-201` 用 `fPerEmoRel.getEmotionBaseline()` 把 personality 折算成 emotion baseline；`MoodEngine.compute()` 的 else（否則）分支負責無事件時把 mood 拉回 default。

### OCC 24 種情緒

Ortony/Clore/Collins 的認知情緒理論（cognitive theory of emotions），`src/de/affect/emotion/EmotionType.java:10-14` 定義 24 種 + `Undefined`（未定義）+ `Physical`（生理），分 7 大類（category，`EmotionType.java:57-86`）：

| 類別 | 情緒 |
|---|---|
| **Well-being（自身順逆）** | Joy（喜悅）/ Distress（苦惱） |
| **Fortunes-of-others（他人順逆）** | HappyFor（為他人開心）/ Gloating（幸災樂禍）/ Resentment（怨恨）/ Pity（同情） |
| **Prospect-based（未來預期）** | Hope（希望）/ Fear（恐懼）/ Satisfaction（滿足）/ Relief（寬慰）/ FearsConfirmed（恐懼成真）/ Disappointment（失望） |
| **Attribution（歸因）** | Pride（驕傲）/ Admiration（崇拜）/ Shame（羞愧）/ Reproach（責備） |
| **Attraction（吸引）** | Liking（喜歡）/ Disliking（厭惡） |
| **Well-being + Attribution** | Gratitude（感激）/ Anger（憤怒）/ Gratification（得意）/ Remorse（悔恨） |
| **Attraction + Attribution** | Love（愛）/ Hate（恨） |

### PAD（Pleasure-Arousal-Dominance，快樂-激發-支配）空間

Mehrabian 提出的三維情感空間（three-dimensional affect space），每軸值域 `[-1.0, 1.0]`。ALMA 把八個 octant（象限）對應到 mood word（心情詞，`src/de/affect/mood/Mood.java:59-86` + `MoodType.java:11`）：

| P | A | D | Mood Word |
|---|---|---|---|
| + | + | + | **Exuberant** 亢奮 |
| + | + | − | **Dependent** 依附 |
| + | − | + | **Relaxed** 放鬆 |
| + | − | − | **Docile** 溫馴 |
| − | + | + | **Hostile** 敵對 |
| − | + | − | **Anxious** 焦慮 |
| − | − | + | **Disdainful** 輕蔑 |
| − | − | − | **Bored** 無聊 |
| 全 0 | | | **Neutral（中性）** |

Mood 強度（intensity）分四檔（`MoodIntensity.java:8`）：`neutral（中性）/ slightly（輕微）/ moderate（中等）/ fully（滿檔）`，切分閾值（threshold）在 `Mood.java:139-147`：
- 向量（vector）長度 `= 0` → neutral
- `(0, 0.50]` → slightly
- `(0.50, 1.00]` → moderate
- `> 1.00` → fully

### Big Five（OCEAN，五大人格）

`src/de/affect/personality/Personality.java:54-102` — 五個 trait（特質）皆 `[-1.0, 1.0]`：

- **O**penness — 開放性
- **C**onscientiousness — 盡責性
- **E**xtraversion — 外向性
- **A**greeableness — 親和性
- **N**euroticism — 神經質

**串接**：`conf/AffectComputationExample.aml:65-70` 的 `<MoodRelations>` 給每個 trait 對 PAD 的權重（weight，採 Mehrabian 1996 的實驗值），加總算 default mood；`<EmotionRelation>` 把 24 種情緒各自映射（mapping）到 PAD 座標，讓 emotion 能推動 mood。

---

## 系統架構

### 主要 package（套件）

```
src/de/affect/
├── manage/       ← AffectManager 主控（1555 行）
├── compute/      ← EmotionEngine / MoodEngine / DecayFunction（衰減函數）
├── appraisal/    ← EEC（Emotion Eliciting Condition，情緒觸發條件）+ 7 個 EEC 變數
├── emotion/      ← EmotionType / EmotionVector / EmotionHistory
├── mood/         ← Mood / MoodType / MoodIntensity
├── personality/  ← Personality / PersonalityEmotionsRelations / PersonalityMoodRelations
├── gui/          ← Swing GUI（AlmaGUI + 10 個 InternalFrame，內嵌視窗）
├── util/         ← AppraisalTag（18 種 tag，標籤分類）
├── xml/          ← XMLBeans generated（自動產生）+ demo classes（示範類別）
└── tools/        ← AffectScriptPlayer / AffectScripts2HTML
```

### 進入點：`AffectManager`

`src/de/affect/manage/AffectManager.java`，1555 行，繼承（extends）`AppraisalManager`（`L132`）。

**三個 constructor（構造子）**：
- `L159` — 無參數：起 GUI，沒載入角色
- `L167` — 檔案路徑：`(compSpec, defSpec, guiFlag)` 標準用法
- `L179` — `InputStream`（輸入串流）版：讓 library 使用者從 memory（記憶體）塞 config

**關鍵 field（欄位）**（`L145-149`）：
- `fNameToCharacter` — `Map<String, CharacterAffect>` 角色索引（index）
- `fNameToGroup` — group affect（群體情感）索引
- `fAvailableEmotionTypes` — 這場計算允許哪些 OCC 情緒

**核心 method（方法）**：

| Method | 位置 | 作用 |
|---|---|---|
| `initComputation()` | `L601` | 載入 `AffectComputation*.aml` 全域參數 |
| `initCharacters()` | `L670` | 從 `AffectDefinition*.aml` 建所有角色 |
| `initCharacter()` | `L692` | 建單一角色（personality + decay function + appraisal 規則） |
| `processSignal(AffectInput)` | `L1282` | **核心 dispatcher（分派器）** — 依 input 類型分派 |
| `startRealtimeOutput()` | `L570` | 開 Timer（定時器），週期由 `<RealtimeOutput period="500">` 控制 |
| `main(args)` | `L1486` | CLI（命令列）入口 |

`processSignal` 支援的 input（輸入）類型（`L1287-1447`）：
- 直接注入 PAD 值
- Act（動作）
- EmotionDisplay / MoodDisplay（外部指定情緒或心情）
- Action / Event / Object（appraisal 三大類）
- BasicEEC（直接餵已計算好的 EEC）

### 計算引擎

**`EmotionEngine`**（`src/de/affect/compute/EmotionEngine.java`, 905 行）：

| Method | 位置 | 作用 |
|---|---|---|
| constructor（構造子） | `L104` | 吃 Personality + DecayFunction |
| `addEEC(EEC)` | `L135` | 收一個 event/action/object EEC，重複會拋 `IllegalStateException`（狀態非法例外） |
| `adjustIntensity()` | `L192` | 用 personality + current mood（當前心情）調整強度（intensity） |
| `decay()` | `L220` | 走 emotion history（情緒歷程），對每個情緒呼叫 decay function |
| `inferEmotions()` | `L277` | 從 EEC list（清單）推 24 種 OCC 情緒（含複合情緒 compound emotion，如 Joy+Admiration→Gratitude） |

**`MoodEngine`**（`src/de/affect/compute/MoodEngine.java`, 18KB）：
- 關鍵 method：`compute(Mood currentMood, EmotionVector emotions)`
- 計算 emotion centroid（情緒質心，`fEmotionsCenter`）在 PAD 空間的位置
- 用 `moveMoodLinear()` 每步推動 mood，一步分 100 個 `sMOODSTEPS` 平滑過渡（smooth transition）
- 無 emotion 時以 `sDEFAULTMOODIMPACT = 0.25` 拉回 default mood
- 若角色開 `neurotismstability="true"` 會呼叫 `randomMoodChange()` 加入隨機擾動（random perturbation）

**三種 decay function（衰減函數）**（`src/de/affect/compute/`）：
- `LinearDecayFunction` — 線性（linear）
- `ExponentialDecayFunction` — 指數（exponential）
- `TangensHyperbolicusDecayFunction` — 雙曲正切（hyperbolic tangent，S 曲線）

對應 `docs/overview.html` 的三張 GIF 曲線圖。

### Listener（監聽器）機制

`src/de/affect/manage/event/` 下四種 listener：
- `AffectUpdateListener` — 週期性 affect（情感）更新（最常用）
- `AffectInputListener` — 有新 input 進來
- `EmotionChangeListener` — 情緒切換（change）
- `EmotionMaintenanceListener` — 情緒 decay 事件（event）

`AffectUpdateEvent.getUpdate()` 回傳 `AffectOutputDocument`（XMLBeans 產物），內含所有角色目前狀態（state）。

### GUI Frame（視窗）

主視窗 `AlmaGUI extends JFrame`（`src/de/affect/gui/AlmaGUI.java:128`），title（標題）"ALMA CharacterBuilder"（角色建構器）。所有子視窗繼承 `AlmaInternalFrame extends JInternalFrame`：

- **AffectMonitorInternalFrame（情感監視器）** — 3D PAD cube（立方體）+ emotion vector 視覺化（Processing + PeasyCam 渲染，600×674）
- **CharacterConfigInternalFrame（角色設定）** — 編輯 personality + appraisal 規則
- **ParamsInternalFrame（參數面板）** — 編輯 Available Acts/Emotions 表（300×500）
- **AffectStatusInternalFrame（情感狀態列）** — 精簡狀態列（300×120）
- **InteractionSimulationInternalFrame（互動模擬器）** — 送 input 給角色
- **GroupAffectMonitorInternalFrame / GroupConfigInternalFrame** — 群體情感
- **ConsoleInternalFrame（主控台）** — log（日誌）主控台
- **HelpInternalFrame / AlmaConfigInternalFrame** — 說明與全域設定

---

## 設定檔詳解

### 全域計算：`conf/AffectComputationExample.aml`

```xml
<AvailableEmotions>                                <!-- L39 -->
  <EmotionSpecification name="Admiration" use="true" docu="Bewunderung"/>
  <!-- ... 24 種 ... -->
</AvailableEmotions>

<MoodRelations docu="Values from Mehrabian (1996)"> <!-- L65 -->
  <OpennessRelation      pleasure="0.16" arousal="0.24" dominance="0.46"/>
  <NeurotismRelation     pleasure="0.43" arousal="-0.49" dominance="0.0"/>
  <!-- ... 其他三個 trait ... -->
  <EmotionRelation name="Anger" pleasure="-0.51" arousal="0.59" dominance="0.25"/>
  <!-- ... 24 種情緒的 PAD 座標 ... -->
</MoodRelations>

<RealtimeOutput enable="true" period="500">        <!-- L102 -->
  <FileLog enable="false" file="docs/affect.log"/>
  <ConsoleLog enable="true"/>
</RealtimeOutput>
```

> 全域檔沒有 `<EmotionDecay>` — decay 參數在角色定義（下一節）。

### 角色定義：`conf/AffectDefinitionExample.aml`

Anne 節錄（`L3-29`）：

```xml
<CharacterAffect name="Anne" docu="New Character" monitored="false">

  <PersonalitySpecification derived="false" emotioninfluence="0.20"
    openness="-0.8" conscientiousness="-0.8" extraversion="1.0"
    agreeableness="0.8" neurotism="0.3"/>

  <MoodSpecification decaytime="600000" decayperiod="250"
    neurotismstability="false"/>

  <EmotionSpecification decaytime="20000" decayperiod="500"
    decayfunction="linear" baseline="0.5"/>

  <Appraisal><Basic>
    <GoodEvent desirability="0.5"/>
    <BadLikelyFutureEvent desirability="-0.5" likelihood="0.5"/>
    <NiceThing appealingness="0.5"/>
    <GoodActOther praiseworthiness="0.5" agency="other"/>
    <!-- ... 18 條 appraisal 規則 ... -->
  </Basic></Appraisal>

</CharacterAffect>
```

同檔還有 `Bob`（`L30-56`，personality 全 0）和 `GroupAffect name="The Two" characters="Anne,Bob"`（`L57-82`）。

### 執行期輸入：`scripts/AffectInputExample.aml`

單一 input（5 行完整）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<AffectInput xmlns="xml.affect.de" ...>
  <Character name="Anne"/>
  <Act addressee="Bruno" listener="Clementine" type="Calm"
    intensity="0.9" elicitor="Test"/>
</AffectInput>
```

### 腳本：`scripts/AffectScriptExample.aml`

帶時間戳（timestamp）的完整 scenario（情境劇本）：

```xml
<aml:AffectScript xmlns:aml="xml.affect.de">
  <aml:Context>Bruno is reorganizing his computer hard drive ...</aml:Context>

  <aml:Item time="0"><aml:ResetCharacter name="Anne"/></aml:Item>
  <aml:Item time="0"><aml:ResetCharacter name="Bruno"/></aml:Item>

  <aml:Item time="1000">
    <aml:AffectInput>
      <aml:Character name="Bruno"/>
      <aml:Event type="BadEvent" intensity="0.80"
        elicitor="lost vacation photos"/>
    </aml:AffectInput>
    <aml:Context>Bruno: Crap, Windows has killed all pictures ...</aml:Context>
  </aml:Item>

  <!-- ... 更多 time-stamped item ... -->
</aml:AffectScript>
```

---

## 完整資料流

以「Bruno 弄丟照片」為例，追一次完整計算：

```
XML input（輸入）
   │  <Event type="BadEvent" intensity="0.80" elicitor="lost vacation photos"/>
   ↓
AppraisalTag.java  (src/de/affect/util/AppraisalTag.java:60-65)
   │  18 種 tag enum（標籤列舉）之一 → BadEvent
   ↓
AppraisalManager.processEvent()
   │  查角色 Appraisal 規則 → desirability（渴望度）= -0.5
   ↓
EEC (src/de/affect/appraisal/EEC.java:68-79)
   │  desirability（渴望度）=-0.5, praiseworthiness（值得讚許度）=?,
   │  appealingness（吸引度）=?, likelihood（可能性）=?,
   │  realization（實現度）=?, liking（好感度）=?, agency（施動者）=?
   ↓
EmotionEngine.addEEC()  (L135)
   ↓
EmotionEngine.inferEmotions()  (L277)
   │  BadEvent + desirability<0 → OCC "Distress"（苦惱）
   ↓
EmotionEngine.adjustIntensity()  (L192)
   │  用 personality（Bruno 的 Neuroticism 神經質）+ 當前 mood 調整強度
   ↓
Emotion 塞入 EmotionVector（情緒向量）
   ↓
MoodEngine.compute()
   │  計算 emotion centroid → 在 PAD 空間拉動 mood 100 步
   ↓
新 Mood → moodword 落到 (-P, +A, -D) 象限 → "Anxious"（焦慮）
   ↓
notifyAffectUpdateListener()  (L527)
   ↓
你的 code：AffectUpdateEvent → getUpdate() 拿到 Bruno 的
             DominantEmotion=Distress, Mood=slightly Anxious
```

---

## 目錄結構

```
ALMA/
├── src/         Java 源碼（source code，11 個 sub-package 子套件）
├── conf/        .aml 全域計算設定 + 預設角色定義 + .ini
├── scripts/     AffectInput / Script / Output 範例
├── docs/        HTML 使用手冊 + EmotionPADMappings.txt + decay GIF
├── xsd/         Affect.xsd — 所有 AML 檔的 schema（結構定義）
├── lib/         依賴 jar（dependency jar）+ 編出的 affect.jar
├── bin/         build.xml (Ant 建置工具) + deploy 腳本
├── data/        執行期（runtime）資料
├── runtime/     打包好的執行版（alma-runtime-v3a.zip）
├── eval/        評估（evaluation）用資料
├── deploy/      對外散布（distribution）用完整包
├── pics/        logo 素材
├── nbproject/   NetBeans 專案設定
├── run.sh       WSL 便利腳本（本地新增）
└── ALMA_專案說明.md  （本檔）
```

---

## 使用方式

### 建置（build）

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
cd /mnt/d/ALMA
ant -f bin/build.xml jar
```

產物：`lib/affect.jar`（Main-Class 主類別：`de.affect.manage.AffectManager`）。

### 執行（用 `run.sh`）

```bash
./run.sh              # CharacterBuilder GUI（Anne + Bob 範例）
./run.sh plain        # 空白 GUI
./run.sh script       # Script Player（腳本播放器）
./run.sh test         # headless（無介面）測試
./run.sh build        # 重編
./run.sh clean        # clean + 重編
./run.sh stop         # 殺掉正在跑的
./run.sh help
```

### 直接 java 指令

```bash
# GUI + 範例角色
java de.affect.manage.AffectManager \
  conf/AffectComputationExample.aml \
  conf/AffectDefinitionExample.aml \
  true

# Headless（無介面）跑腳本
java de.affect.manage.AffectManager \
  conf/BasicComputation.aml \
  conf/BasicDefinition.aml \
  false \
  scripts/AffectInputExample.aml
```

### Library（函式庫）嵌入（最小骨架）

```java
AffectManager am = new AffectManager(
    "conf/AffectComputation.aml",
    "conf/CharacterDefinition.aml",
    false);

am.addAffectUpdateListener(evt -> {
  for (CharacterAffect c :
       evt.getUpdate().getAffectOutput().getCharacterAffectList()) {
    System.out.println(c.getName()
      + " → " + c.getDominantEmotion().getName()
      + " / mood=" + c.getMood().getMoodword());
  }
});

AffectInput ai = AppraisalTag.instance()
    .makeAffectInput("Bruno", "GoodEvent", "1.0", "The sun is shining");
am.processSignal(ai);
```

完整範例：`docs/deploy/AffectEngine.java`、`src/de/affect/xml/demo/CreateAffectInput.java`。

---

## 已知技術債與坑

本地 clone（複製）已修的兩處：

1. **`src/de/affect/gui/ParamsInternalFrame.java:511`** — Vector 泛型（generics）cast（轉型）
   ```java
   // 原本（新 JDK 編不過）
   Vector<Vector<Object>> table = (Vector<Vector<Object>>) getDataVector();
   // 修正（加一層 raw cast，原始型別轉型）
   Vector<Vector<Object>> table = (Vector<Vector<Object>>) (Vector) getDataVector();
   ```

2. **`bin/build.xml`** `alma` target（目標）`includes` — 原本少列 4 個 package
   補上 `de/affect/appraisal/**`、`de/affect/emotion/**`、`de/affect/mood/**`、`de/affect/personality/**`，不然 `MoodIntensity.class` 等 enum（列舉）不會被 javac（Java 編譯器）打包進 `affect.jar`，一啟動就 `NoClassDefFoundError`（類別定義找不到例外）。

其他小坑：

- **`MoodEngine.java` 被壓成單行**：18KB 程式碼、`wc -l` 回 0，要靠編輯器 auto-format（自動格式化）才讀得懂
- **德文遺跡**：`AffectComputationExample.aml` 所有 `docu=` 都是德文（Bewunderung 崇拜、Schadenfreude 幸災樂禍）
- **Java3D 相依（dependency）脆弱**：Java3D 2008 年就被 Sun 棄坑，換 JDK 版本或作業系統時 Affect Monitor 3D 視窗可能開不起來 — 這時可退回 `gui=false` console（主控台）模式
- **WSL 上跑 GUI**：需要 WSLg（Windows 11 內建）或 X server（X 顯示伺服器）。本專案在 WSL2 + WSLg + JDK 11 已驗證可跑

---

## 學習路徑建議

**第一週：跑起來、看得懂輸出**
1. `./run.sh` 開 GUI，玩 Anne / Bob，觀察 mood cube（心情立方體）3D 視覺化
2. `./run.sh script` 跑 `AffectScriptExample.aml`，看 Bruno 情緒隨腳本變化
3. 讀 `docs/index.html` → `docs/alma.html` → `docs/affectglossar.html`（情感詞彙表）三份 HTML 手冊
4. 對照 CharacterBuilder GUI 找出每個 InternalFrame 對應的功能

**第二週：理論**
5. 讀 OCC 原始論文（Ortony/Clore/Collins 1988, *The Cognitive Structure of Emotions*，情緒的認知結構）
6. 讀 Mehrabian 1996 的 PAD 論文，理解 8 個象限的心理學基礎
7. 看 `docs/EmotionPADMappings.txt` — 24 種情緒 vs PAD 座標的映射（mapping）表
8. 讀 Gebhard 2005 論文 *ALMA: A Layered Model of Affect*，AAMAS 會議

**第三週：程式碼**
9. 從 `AffectManager.main()`（`L1486`）追下去到 `initComputation` → `initCharacters`
10. 追一次 `processSignal(AffectInput)`（`L1282`）的完整 dispatch（分派）
11. 讀 `EmotionEngine.inferEmotions()` — 24 種 OCC 的判斷邏輯（logic）
12. 開 editor（編輯器）auto-format `MoodEngine.java`，讀 `compute()` 的 mood 移動演算法（algorithm）

**第四週：改造**
13. 定義自己的角色（改 `AffectDefinition*.aml`）
14. 寫自己的 script（腳本，`AffectScript*.aml`）驗證特定情境
15. 寫 library 嵌入範例：Listener 接輸出、丟給下游系統（downstream system）
16. 若要換視覺化（visualization）：抽掉 Java3D，改用現代 stack（技術堆疊，如 JavaFX 3D 或 web-based）

---

## 參考資源

- 專案原始 site（網站，已下線）：DFKI Patrick Gebhard research page
- GitHub: https://github.com/A-L-M-A/ALMA
- OCC 情緒理論：Ortony, Clore, Collins (1988)
- PAD 空間：Mehrabian (1980, 1996)
- Big Five：Costa & McCrae (1992)
- ALMA 論文：Gebhard (2005), *ALMA – A Layered Model of Affect*（分層情感模型）, AAMAS'05
