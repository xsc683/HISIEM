# MITRE ATT&CK 覆盖矩阵

> 状态:Phase 3.5 · 2026-08-16
> 记录当前检测规则对 MITRE ATT&CK 战术/技术的覆盖情况(Detected / Logged / Blind),用于"看得见覆盖盲区"、按威胁优先级排新规则。

## 1. 覆盖矩阵

| 战术 | 技术 | 技术 ID | 状态 | 检测规则 |
| --- | --- | --- | --- | --- |
| Credential Access | Brute Force: Password Guessing | **T1110.001** | Detected | rule-ssh-auth-failure-001、rule-ssh-brute-force-001、rule-ssh-bruteforce-success-001、rule-auth-rate-anomaly-001(基线) |
| Initial Access | Valid Accounts | **T1078.002** | Detected | rule-ssh-bruteforce-success-001(成功登录)、rule-root-login-failure-001、rule-common-user-bruteforce-001 |
| Privilege Escalation | Exploitation for Privilege Escalation | **T1068** | Detected | rule-root-login-failure-001(root 登录失败映射 t1068 存疑) |

> 状态含义:**Detected** = 有规则直接识别该技术;**Logged** = 仅有原始日志覆盖、无检测规则;**Blind** = 既无日志也无检测。

## 2. ATT&CK Navigator layer

以下 layer JSON 可导入 [ATT&CK Navigator](https://mitre-attack.github.io/attack-navigator/) 查看覆盖热力图:

```json
{
  "name": "HISIEM 检测覆盖 (Phase 3.5)",
  "versions": {
    "attack": "16",
    "navigator": "5.2.0",
    "layer": "4.5"
  },
  "domain": "enterprise-attack",
  "description": "HISIEM(ES+Logstash+Kafka+Flink)当前检测规则对 MITRE ATT&CK 的覆盖",
  "filters": {
    "platforms": ["Linux"]
  },
  "techniques": [
    {
      "techniqueID": "T1110.001",
      "tactic": "credential-access",
      "score": 100,
      "color": "#66b3ff",
      "comment": "SSH 认证失败 / 暴力破解窗口 / 暴力破解成功攻击链 / 认证失败率基线异常(rule-auth-rate-anomaly-001)"
    },
    {
      "techniqueID": "T1078.002",
      "tactic": "initial-access",
      "score": 90,
      "color": "#66b3ff",
      "comment": "有效账户(本地):成功登录(攻击链)、root/常见弱账号"
    },
    {
      "techniqueID": "T1068",
      "tactic": "privilege-escalation",
      "score": 70,
      "color": "#66b3ff",
      "comment": "root 登录失败检测(映射 t1068 存疑)"
    }
  ],
  "gradient": {
    "colors": ["#ffff99", "#66b3ff"],
    "minValue": 0,
    "maxValue": 100
  },
  "showTacticRowBackground": true,
  "tacticRowBackground": "#dddddd"
}
```

## 3. 覆盖盲区(按威胁优先级排新规则的输入)

当前仅覆盖 **认证(Initial Access / Credential Access)** 一个场景。高价值待覆盖方向:

| 优先级 | 技术 | 场景 | 数据前提 | 检测逻辑思路 |
| --- | --- | --- | --- | --- |
| P1 | T1110.003(Password Spraying) | 同源多用户失败尝试 | 已有(event.action=authentication_failure) | 同源 IP 短时间对 ≥N 个不同 user.name 失败 |
| P1 | T1078.002(有效账户,加时间上下文) | 时间上下文成功登录 | 已有(authentication_success) | 非工作时段 / 异常地域成功登录 |
| P2 | T1059(命令执行) | 任意 shell/命令执行 | 需新数据源(bash history / auditd) | bash history / execve 审计 |
| P2 | T1046(端口扫描) | 网络扫描 | 需新数据源(网络流日志) | 连接流阈值扫描 |

### 3.1 已触达数据源可看、但未覆盖的技术(Blind)

SSH 认证日志已带出部分字段,下列技术"数据上可见"但暂无检测规则,统一标 **Blind**:

| 技术 | 战术 | 场景(已有字段) | 检测逻辑思路 |
| --- | --- | --- | --- |
| T1021(Remote Services) | Lateral Movement | authentication_success(SSH 登录) | 需命令执行 / 会话审计方能判定后续远程行为 |
| T1552(Unsecured Credentials) | Credential Access | authentication_failure(user.name) | 口令喷洒:多账号短时间内同源失败 |
| T1110.004(Credential Stuffing) | Credential Access | authentication_failure(多源同账号) | 多源 IP 对同一 user.name 尝试失败 |
| T1078.001(Default Accounts) | Initial Access | authentication_success(user.name) | 默认账号(admin/test/guest)成功登录 |
| T1078.002(Local Accounts) | Initial Access | user.name(非域账号) | 本地账号行为基线偏离 |
| T1078.004(Cloud Accounts) | Initial Access | 无云字段 | 需云审计数据源 |
| T1189(Drive-by Compromise) | Initial Access | 无 Web/终端字段 | 需 Web/邮件代理日志 |
| T1190(Exploit Public-Facing Application) | Initial Access | 无服务访问日志 | 需应用/WAF 日志 |

> 原则:先确定性规则(已有数据的场景),再新数据源,不做 UEBA/ML。

## 4. 覆盖维护流程(checklist)

检测即代码:规则的 MITRE tags 单一来源为 RuleRegistry / 规则 YAML。新增或修改覆盖标注时按此流程,保证矩阵 / Navigator / 代码三者自洽:

1. **改 tags**:在 RuleRegistry(或规则 YAML)中新增/修改规则的 MITRE tags(如 `attack.t1110.001`)。
2. **更新 §1 矩阵行**:同步覆盖矩阵对应技术行的状态与检测规则列。
3. **更新 §2 Navigator JSON**:同步对应 techniqueID 的 score / color / comment。
4. **提交**:连同规则变更一起 commit + PR,附覆盖变化说明。

> 已触达数据源但暂无规则的技术在 §3 盲区表标 Blind,待排规则后回填矩阵。
