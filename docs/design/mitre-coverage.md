# MITRE ATT&CK 覆盖矩阵

> 状态:Phase 3.2 · 2026-08-16
> 记录当前检测规则对 MITRE ATT&CK 战术/技术的覆盖情况(Detected / Logged / Blind),用于"看得见覆盖盲区"、按威胁优先级排新规则。

## 1. 覆盖矩阵

| 战术 | 技术 | 技术 ID | 状态 | 检测规则 |
| --- | --- | --- | --- | --- |
| Credential Access | Brute Force: Password Guessing | **T1110.001** | Detected | rule-ssh-auth-failure-001、rule-ssh-brute-force-001、rule-ssh-bruteforce-success-001 |
| Initial Access | Valid Accounts | **T1078** | Detected | rule-ssh-bruteforce-success-001(成功登录)、rule-root-login-failure-001、rule-common-user-bruteforce-001 |
| Privilege Escalation | Exploitation for Privilege Escalation | **T1068** | Logged(间接) | rule-root-login-failure-001(仅关联日志,无独立检测) |

> 状态含义:**Detected** = 有规则直接识别该技术;**Logged** = 仅有原始日志覆盖、无检测规则;**Blind** = 既无日志也无检测。

## 2. ATT&CK Navigator layer

以下 layer JSON 可导入 [ATT&CK Navigator](https://mitre-attack.github.io/attack-navigator/) 查看覆盖热力图:

```json
{
  "name": "HISIEM 检测覆盖 (Phase 3.2)",
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
      "techniqueID": "T1110",
      "tactic": "credential-access",
      "score": 100,
      "color": "#66b3ff",
      "comment": "SSH 认证失败 / 暴力破解窗口 / 暴力破解成功攻击链"
    },
    {
      "techniqueID": "T1078",
      "tactic": "initial-access",
      "score": 90,
      "color": "#66b3ff",
      "comment": "有效账户:成功登录(攻击链)、root/常见弱账号"
    },
    {
      "techniqueID": "T1068",
      "tactic": "privilege-escalation",
      "score": 20,
      "color": "#ffff99",
      "comment": "仅关联日志(root 失败),无独立检测"
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

| 优先级 | 技术 | 场景 | 数据前提 |
| --- | --- | --- | --- |
| P1 | T1110.003(用户名枚举) | 同源多用户失败尝试 | 已有(event.action=authentication_failure) |
| P1 | T1078(非工作时间登录) | 时间上下文成功登录 | 已有(authentication_success) |
| P2 | T1059(命令执行) | 任意 shell/命令执行 | 需新数据源(bash history / auditd) |
| P2 | T1046(端口扫描) | 网络扫描 | 需新数据源(网络流日志) |

> 原则:先确定性规则(已有数据的场景),再新数据源,不做 UEBA/ML。
