# 多版本客户端 API 兼容与幂等网关（本地实现）

一套纯本地、零外部依赖的 Spring Boot 网关能力：客户端声明期望版本后调用同一业务能力，
网关在**所有调用入口**下以一致、可解释的方式完成版本协商、重复提交防护、权限判定、
异常映射与审计。不依赖任何外部服务、数据库或真实凭据。

- 业务能力示例：创建订单（`ORDER_CREATE`）
- 调用入口：
  - REST 入口 `POST /api/orders`
  - 通用入口 `POST /gateway/invoke`（两个入口共用同一个门面 `GatewayFacade`）

---

## 1. 版本协商与弃用规则

客户端通过请求头 `X-Api-Version: major.minor` 声明期望版本。

| 已发布版本 | 状态 | 处理路径 |
|-----------|------|---------|
| `1.0` | `SUPPORTED`（受支持的旧客户端） | v1 处理路径 |
| `1.9` | `DEPRECATED`（已弃用，仍可调用） | v1 处理路径 |
| `2.0` | `SUPPORTED`（当前版本） | v2 处理路径 |

协商规则：

1. **缺失版本头**（无 `X-Api-Version` 或空白）→ 拒绝，错误码 `VERSION_MISSING`（HTTP 400）。
2. **未知版本**：
   - 头值无法解析（如 `not-a-version`）；
   - 或版本可解析、主版本存在但该次版本未发布（如 `1.5`）；
   → 拒绝，错误码 `VERSION_UNKNOWN`（HTTP 400）。
3. **跨主版本不兼容**：主版本无任何处理路径（如 `3.0`）
   → 拒绝，错误码 `VERSION_INCOMPATIBLE`（HTTP 400），错误信息附带受支持主版本列表。
4. **已弃用版本不被拒绝**：`1.9` 正常处理，同时返回明确弃用信号响应头：
   - `Deprecation: true`
   - `Sunset: Wed, 31 Mar 2027 00:00:00 GMT`
   - `Link: ...; rel="successor-version"; version="2.0"`（REST 入口）
   - 响应体 `deprecated=true`
5. 弃用版本**不影响**仍受支持的旧客户端：`1.0` 继续正常返回，且不带弃用信号。
6. 网关按**主版本**选择兼容处理路径：`1.x → v1`、`2.x → v2`，
   响应体 `data.handlerVersion` 与 `data.orderId` 前缀可验证实际选中的路径。

---

## 2. 幂等语义与并发约束

所有写请求必须携带 `Idempotency-Key` 头（缺失 → `IDEMPOTENCY_KEY_MISSING`，HTTP 400）。

请求指纹：请求体 JSON 按 key 规范化后做 SHA-256。同一键下指纹不同即视为键冲突。

### 状态机（同一幂等键）

```
(无记录) ──首次请求──────────► IN_PROGRESS ──成功──► SUCCEEDED（结果冻结）
                                 │  ▲                ▲
                                 └──┤失败            │同键同请求体
                                    ▼                │
                                  FAILED ──重试──────┘（RETRY_AFTER_FAILURE）
```

| 情况 | 幂等状态 | HTTP / 信号 |
|------|---------|-------------|
| 首次提交并执行成功 | `NEW` | 200 |
| 请求处理进行中的重试（并发重复） | `IN_PROGRESS` | 409 `IDEMPOTENCY_IN_PROGRESS`，立即返回、不排队等待 |
| 成功后重复提交（同键同体） | `REPLAY_SUCCEEDED` | 200，返回与首次**一致的冻结结果**；响应头 `Idempotent-Replay: true`、`X-Idempotency-Status` |
| 失败后同键同体重试，重试成功 | `RETRY_AFTER_FAILURE` | 200（首次失败为 422 `BUSINESS_ERROR`） |
| 同键但请求体不同 | `KEY_CONFLICT` | 409 `IDEMPOTENCY_KEY_CONFLICT`，既有记录/结果不受影响 |

### 并发实现约束（`InMemoryIdempotencyStore`）

- `ConcurrentHashMap<幂等键, 记录>` + 每键一把锁。
- **锁内决策、锁外执行、锁内提交**：
  - 记录检查/创建在键锁内完成，同一键只有一个请求成为执行者；
  - 业务动作在键锁**外**执行，使并发请求能观察到 `IN_PROGRESS` 而不是阻塞等待；
  - 动作结束后重新进入键锁提交 `SUCCEEDED/FAILED`，结果不会被覆盖或丢失。
- 成功记录被视为冻结结果，回放绝不再次产生副作用。
- 失败不产生业务副作用；仅失败后允许同键同体重新执行。

---

## 3. 权限判定方式

- 客户端通过 `X-Client-Id` 标识；能力范围来自**本地固定目录** `InMemoryClientDirectory`
  （无外部授权服务、无真实凭据）：
  - `client-a`：具备 `ORDER_CREATE`
  - `client-b`：已注册但无 `ORDER_CREATE`
- 判定依据（写入审计 `reason`，可区分）：
  - 未提供标识 → `missing client identity`
  - 未注册客户端 → `unknown client`
  - 已注册但缺少能力 → `capability not granted (...)`
  - 放行 → `capability granted`
- **不泄露资源是否存在**：以上任何一种拒绝，对外统一映射为
  `404 RESOURCE_NOT_FOUND`，消息固定为 `resource not found`；真实原因只出现在服务端审计中。

处理顺序固定：**版本协商 → 权限判定 → 幂等键/执行 → 审计**。

---

## 4. 错误契约

所有错误返回稳定 JSON 契约，并带 `X-Request-Id` 响应头（与成功响应、审计记录中的请求标识一致）：

```json
{
  "requestId": "uuid",
  "code": "VERSION_INCOMPATIBLE",
  "message": "major version 3 is incompatible; supported majors: [1,2]",
  "details": { "supportedVersions": { "1.0": "SUPPORTED", "1.9": "DEPRECATED ...", "2.0": "SUPPORTED" } },
  "timestamp": "2026-09-26T00:00:00Z"
}
```

| code | HTTP | 含义 |
|------|------|------|
| `VERSION_MISSING` | 400 | 未声明版本 |
| `VERSION_UNKNOWN` | 400 | 版本无法解析或未发布 |
| `VERSION_INCOMPATIBLE` | 400 | 跨主版本不兼容 |
| `IDEMPOTENCY_KEY_MISSING` | 400 | 缺少幂等键 |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | 同键请求仍在处理中 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 同键被用于不同请求体 |
| `BUSINESS_ERROR` | 422 | 业务异常（失败记录可重试） |
| `VALIDATION_ERROR` | 400 | 请求参数非法 / JSON 格式错误 |
| `RESOURCE_NOT_FOUND` | 404 | 资源不存在（**也用于鉴权拒绝**，避免存在性泄露） |
| `INTERNAL_ERROR` | 500 | 未预期异常（不回传堆栈等内部细节） |

成功响应契约：

```json
{
  "requestId": "uuid",
  "resolvedVersion": "2.0",
  "deprecated": false,
  "idempotency": { "key": "...", "status": "NEW|RETRY_AFTER_FAILURE|REPLAY_SUCCEEDED|...", "replayed": false },
  "data": { "orderId": "v2-order-...", "item": "...", "quantity": 1, "handlerVersion": "2", "createdAt": "..." }
}
```

### 审计

每次调用（含所有拒绝路径）都写一条审计事件（SLF4J logger 名 `AUDIT`，同时保留在内存缓冲供测试断言），字段包含：
请求标识、客户端、能力、原始版本头、**版本决策**（状态/请求版本/解析版本/弃用/理由）、
**幂等状态**、**权限判定依据**（allowed/reason）、最终结果。

---

## 5. 本地验证方法

要求 JDK 21+、Maven 3.9+（全程无需网络上的外部服务；首次构建需 Maven 解析依赖）。

```bash
# 编译
mvn compile

# 运行全部测试（28 个：单测 + Spring 集成测试）
mvn test

# 只跑版本协商单测
mvn test -Dtest=VersionNegotiationTest

# 只跑幂等并发单测（含同键 8 线程并发：NEW=1 / IN_PROGRESS=7 / 副作用=1）
mvn test -Dtest=IdempotencyConcurrencyTest

# 只跑 HTTP 端到端测试（含真实 HTTP 并发、失败重试、未授权、弃用信号）
mvn test -Dtest=OrderApiIntegrationTest,OrderApiConcurrencyTest,EntryPointConsistencyTest
```

测试日志（控制台可见）会按用例打印：请求标识、版本决策、幂等状态、权限判定依据。

### 手动 curl 验证（可选）

```bash
mvn spring-boot:run   # 使用随机端口，见 application.properties；可改为 server.port=8080

# 受支持旧版本
curl -i -X POST localhost:8080/api/orders \
  -H 'X-Api-Version: 1.0' -H 'X-Client-Id: client-a' -H 'Idempotency-Key: k1' \
  -H 'Content-Type: application/json' -d '{"item":"book","quantity":1}'

# 已弃用版本（注意 Deprecation/Sunset 响应头）
curl -i -X POST localhost:8080/api/orders \
  -H 'X-Api-Version: 1.9' -H 'X-Client-Id: client-a' -H 'Idempotency-Key: k2' \
  -H 'Content-Type: application/json' -d '{"item":"book","quantity":1}'

# 同键重复提交（第二次返回相同 data 且带 Idempotent-Replay: true）
curl -i -X POST localhost:8080/api/orders \
  -H 'X-Api-Version: 2.0' -H 'X-Client-Id: client-a' -H 'Idempotency-Key: k3' \
  -H 'Content-Type: application/json' -d '{"item":"pen","quantity":2}'
curl -i -X POST localhost:8080/api/orders \
  -H 'X-Api-Version: 2.0' -H 'X-Client-Id: client-a' -H 'Idempotency-Key: k3' \
  -H 'Content-Type: application/json' -d '{"item":"pen","quantity":2}'

# 跨主版本不兼容
curl -i -X POST localhost:8080/api/orders \
  -H 'X-Api-Version: 3.0' -H 'X-Client-Id: client-a' -H 'Idempotency-Key: k4' \
  -H 'Content-Type: application/json' -d '{"item":"book","quantity":1}'

# 未授权（与资源不存在返回完全相同）
curl -i -X POST localhost:8080/api/orders \
  -H 'X-Api-Version: 2.0' -H 'X-Client-Id: client-b' -H 'Idempotency-Key: k5' \
  -H 'Content-Type: application/json' -d '{"item":"book","quantity":1}'
```

---

## 6. 主要代码结构

```
model/        版本、幂等、审计、错误契约等领域模型
version/      版本注册表与协商规则、按主版本的处理器接口
auth/         本地客户端目录与能力范围判定
idem/         幂等存储（键级锁状态机）、请求指纹
audit/        审计日志（SLF4J + 内存缓冲）
web/          统一门面、两个入口 Controller、全局异常处理、Spring 接线
web/handler/  v1 / v2 业务处理路径与副作用计数器
```
