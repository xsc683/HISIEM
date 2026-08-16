# 威胁情报(TI)富化方案

> 状态:Phase 3.5 · 2026-08-16
> 轻量 TI 查表富化(不做独立 TI 平台)。原则:先用本地查表,需要更实时时再升级,避免过早引入重型 feed 基建。

## 1. 定位

- **不做**:独立威胁情报平台(STIX/TAXII 拉 feed、MISP 等)——对单机 lab 过重。
- **做**:IP 信誉查表,at-ingest 富化,让检测规则可用"该 IP 是否已知恶意/扫描器"上下文。

## 2. 方案:本地 CSV 查表(at-ingest)

| 项 | 值 |
| --- | --- |
| 数据源 | AbuseIPDB / GreyNoise 导出的 IP 信誉 CSV(每日更新) |
| 落地 | Logstash `translate` filter(查 source.ip) |
| 输出 | `threat.is_malicious`(bool)/`threat.confidence`(0-1) |
| 优势 | 零基础设施、规则直接可用、与 at-ingest 富化(GeoIP)一致 |

### 示例(Logstash translate)

```ruby
translate {
  source => "source.ip"
  target => "threat"
  dictionary_path => "/usr/share/logstash/data/ip-reputation.csv"
  refresh_interval => 3600   # 每小时重载,配合每日更新
  fallback => "unknown"
}
```

`ip-reputation.csv` 格式:`"8.8.8.8",true,0.9`(IP,is_malicious,confidence)。

> 注:Logstash translate 的 CSV 字典列名默认 `map[source]`/`map[target]`;可通过 `dictionary_file` 的 header 或 `exact`/`regex` 配置调整。

## 3. 升级路径

1. **CSV translate**(起步,本方案)——每日手动/脚本更新 CSV,Logstash 每小时重载。
2. **Flink AsyncFunction 异步查**——需要更实时(毫秒级)或更大字典时,在检测前异步查。
3. **STIX/TAXII feed 平台**——多数据源、IoC 生命周期管理、关联分析时再引入(独立项目)。

## 4. 误用提示

- GreyNoise 等"大众扫描器"分类可显著降低互联网背景噪声误报,与 TI 配合使用。
- 查表结果要进检测规则前,先验证 TI 数据质量(误报/漏报),避免"用不可靠 TI 扩大误报"。
