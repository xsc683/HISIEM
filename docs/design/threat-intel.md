# 威胁情报(TI)富化方案

> 状态:Phase 3.5 · MVP 已落地(2026-08-16:Logstash translate 本地字典,见 infra/logstash/config/ti-*.yml 与 infra/ti/update-ti.py)
> 轻量 TI 查表富化(不做独立 TI 平台)。原则:先用本地查表,需要更实时时再升级,避免过早引入重型 feed 基建。

## 1. 定位

- **不做**:独立威胁情报平台(STIX/TAXII 拉 feed、MISP 等)——对单机 lab 过重。
- **做**:IP 信誉查表,at-ingest 富化,让检测规则可用"该 IP 是否已知恶意/扫描器"上下文。

## 2. 方案:本地 CSV 查表(at-ingest)

| 项 | 值 |
| --- | --- |
| 数据源 | AbuseIPDB / GreyNoise 导出的 IP 信誉 CSV(每日更新;**MVP 用手工 YAML 字典,外部 feed 拉取 P2+ 未做**) |
| 落地 | Logstash `translate` filter(查 source.ip) |
| 输出 | `threat.is_malicious`(bool)/`threat.confidence`(0-1) |
| 优势 | 零基础设施、规则直接可用、与 at-ingest 富化(GeoIP)一致 |

> **MVP 现状(2026-08-16,`664f6a6`)**:已落地本地字典版——`infra/logstash/config/ti-malicious.yml` / `ti-confidence.yml`(YAML 而非下述 CSV),`logstash.conf` 用两个 `translate` filter(与下述示例同构,refresh_interval 3600 + fallback);字典更新脚本 `infra/ti/update-ti.py`。下述 download.sh/build-csv.py + 外部 feed 为 **P2+ 升级路径,未实现**;YAML 与 CSV 可互相转换,字典格式不影响 filter 逻辑。

### 示例(Logstash translate,可运行)

用**两个 translate**分别落 `threat.is_malicious` 和 `threat.confidence`(各自字典是单列 target 的 CSV,最稳):

```ruby
filter {
  # 恶意标记:命中且值为 "true" → threat.is_malicious="true";未命中 → fallback "false"(unknown 视为非恶意;字符串保持一致)
  translate {
    source => "source.ip"
    target => "threat.is_malicious"
    dictionary_path => "/usr/share/logstash/data/ip-malicious.csv"
    refresh_interval => 3600   # 每小时重载,配合每日更新
    fallback => "false"
  }
  # 置信度:命中 → "0.0"-"1.0";未命中 → fallback "0"(unknown = 查不到,置信度记 0;字符串保持一致)
  translate {
    source => "source.ip"
    target => "threat.confidence"
    dictionary_path => "/usr/share/logstash/data/ip-confidence.csv"
    refresh_interval => 3600
    fallback => "0"
  }
}
```

两个字典 CSV 的 header 都是完整的 `map[source],map[target]`(第一列 = 查询键,第二列 = 目标值):

```
# ip-malicious.csv
map[source],map[target]
8.8.8.8,true
8.8.8.9,false

# ip-confidence.csv
map[source],map[target]
8.8.8.8,0.9
8.8.8.9,0.2
```

> 说明:
> - **字典值始终为字符串**:translate 从 CSV 字典读出的值是**字符串**(`"true"`/`"false"`/`"0.9"`),不是 bool/number;**fallback 也用同类型字符串保持一致**(如 `fallback => "false"` / `fallback => "0"`,见上例),否则命中(字符串)与未命中(bool/number)类型不一致、下游比较错位;下游 `FieldEqualsCondition` 同样按字符串比较(见 §4)。
> - 字典用 `dictionary_path` 指向文件;**没有 `dictionary_file` 这个选项**(常见误写)。
> - **unknown 语义**:IP 不在字典里 → `threat.is_malicious="false"`、`threat.confidence="0"`,即"未知,不判定恶意";绝不把 unknown 当恶意,避免扩大误报。
> - 想合并成一个 translate 时,可用 JSON/YAML 字典让目标值为嵌套结构(如 `{"8.8.8.8": {"is_malicious": true, "confidence": 0.9}}`),`target => "threat"` 一次写入(嵌套字典值同样按字符串处理)。
> - **CIDR 局限**:translate 是精确匹配,`1.2.3.0/24` 这类网段匹配不了。起步阶段只查单 IP、忽略 CIDR;CIDR 覆盖留到 Flink AsyncFunction 阶段(见 §3)。

### 数据更新流水线(字典如何产生)

每日下载 → 转标准 CSV → 校验 → 落地字典文件 → translate 每小时自动 reload(无需重启 logstash):

1. **下载**:`infra/ti/download.sh` 从 AbuseIPDB / GreyNoise 拉 IP 信誉 CSV(各自需 API key,key 走环境变量,文件不进 git)。
2. **转换**:`infra/ti/build-csv.py` 把原始行转成上面两个字典 CSV(`ip`→`is_malicious`、`ip`→`confidence`),按置信度阈值(如 **AbuseIPDB 置信度 ≥25** 才判 `is_malicious`)从源头控误标。
3. **校验**:转换后校验行数(如 >1000 且 0 转换失败),低于阈值视为下载/解析异常 → **保留旧字典文件,不覆盖**。
4. **落地**:把两个 CSV 复制进 logstash 字典目录(compose 挂载 `logstash-data` 卷 → `/usr/share/logstash/data/`),`translate` 的 `refresh_interval => 3600` 每小时自动重载即生效,无需重启:
   `docker cp infra/ti/ip-malicious.csv siem-logstash:/usr/share/logstash/data/`(及 ip-confidence.csv)。
5. **cron 每日**:`0 4 * * * bash /mnt/d/Project/SIEM/infra/ti/refresh.sh`;第 3 步校验不通过即失败,发通知并保留旧字典继续服务。

## 3. 升级路径

1. **CSV translate**(起步,本方案)——每日脚本更新 CSV,Logstash 每小时重载。**触发判据**:字典条数增长到几十万级、或单事件富化延迟成为瓶颈、或 TI 命中率长期偏低(字典太薄)时升级。
2. **Flink AsyncFunction 异步查**——需要毫秒级实时、更大字典、或 CIDR 网段匹配时,在检测前异步查。要点:
   - **timeout**:单次查询设超时(如 100ms),超时按 unknown 降级,不阻塞主流程。
   - **capacity/并发**:受限并发(如 50-100),防外部服务被打爆。
   - **缓存**:IP→TI 结果内存 LRU 缓存,热点 IP 复用。
   - **熔断/降级**:外部服务持续超时或 5xx 时熔断,降级为"全部 unknown"继续跑,不拖垮检测。
   - 降级语义与 translate 的 fallback 保持一致(unknown = 非恶意)。
3. **STIX/TAXII feed 平台**——多数据源、IoC 生命周期管理、关联分析时再引入(独立项目)。

## 4. 检测规则 / 告警联动

TI 富化结果直接给检测规则和告警用:

- **规则条件**：检测规则用 YAML 声明 `field_equals`，由 `RuleConfigLoader`/`RuleBuilder` 构造类型化条件。例如 `threat.is_malicious="true"` 时单独告警或升风险（**translate 输出为字符串，因此用字符串 `"true"`，不用布尔 `true`**，与 §2 fallback 类型一致）：
  ```yaml
  id: rule-threat-intel-001
  name: 命中威胁情报 IP
  category: single_event
  type: threat_intel_match
  enabled: true
  severity: high
  description: source.ip 命中威胁情报
  condition:
    type: field_equals
    field: threat.is_malicious
    value: "true"
  ```
- **threat.\* 进告警的取舍**:
  - 进:告警带 `threat.is_malicious` / `threat.confidence`,分析师不用回事件库即可判断;也便于按 TI 命中率统计。
  - 取舍:TI 本身有误标,带进告警会把噪声扩散——只带结论字段(`is_malicious`/`confidence`),不带 TI 原始明细;并把 TI 相关误报按 `alert.analyst_verdict` 统计回流(见 §5)。
- **mapping 注**(与已实现状态一致):`infra/elasticsearch/siem-events-template.json` **当前无 `threat` 字段**;translate 输出为字符串,动态映射会把 `threat.is_malicious` 按字符串(keyword/text)处理,无法按 bool/float 聚合与阈值比较;要按 bool/float 聚合与阈值比较,需在模板里补显式映射(模板只对之后新建的索引生效,按天索引新一天自动套用,已建旧索引不回溯):
  ```json
  "threat": {
    "properties": {
      "is_malicious": { "type": "boolean" },
      "confidence":   { "type": "float" }
    }
  }
  ```

## 5. 数据质量验证与误用提示

查表结果进检测规则前,先验证 TI 数据质量,避免"用不可靠 TI 扩大误报":

- **抽 N 条人工打标算 precision**:随机抽 N 条(如 100-200)命中 TI 的事件,人工核对 `source.ip` 是否真恶意,`precision = 真恶意数 / N`;低于阈值(如 <0.9)要换数据源或调阈值。
- **TI 命中率**:`命中条数 / 全部事件`,过低说明字典太薄/更新不及时;过高要排查是不是把正常流量也标了。
- **置信度阈值**:confidence 低于阈值(如 **≥0.7 才判恶意**)不标,在 build-csv.py 生成字典时按此阈值决定 `is_malicious`,从源头控制误标。
- **FP 回流**:TI 相关误报按 `alert.analyst_verdict=false_positive` 统计,FP 率偏高的规则优先 review(见 triage-alert.py 的误报闭环)。
- GreyNoise 等"大众扫描器"分类可显著降低互联网背景噪声误报,与 TI 配合使用。
