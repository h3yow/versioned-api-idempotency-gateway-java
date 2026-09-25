# 多版本 API 兼容与幂等网关（本地实现）

面向多版本客户端的本地 API 兼容与幂等处理能力：**版本协商、重复提交、权限判定、异常返回**
在所有调用入口（HTTP 端点或测试直连）下保持一致且可解释。纯本地实现，不依赖外部服务或真实凭据
（客户端目录与版本表均为进程内登记，`server.port=0` 随机端口启动）。

## 1. 能力总览

| 能力 | 入口/组件 | 说明 |
| --- | --- | --- |
| 版本协商 | `VersionNegotiator` + `VersionRegistry` | 按 `X-Api-Version` 选择兼容处理路径，拒绝原因稳定可区分，弃用版本给信号但继续服务 |
| 幂等处理 | `IdempotencyService` + `IdempotencyStore` | 按 `Idempotency-Key` 去重；每键一把锁 + 终态 CAS，同键并发只产生一次副作用 |
| 权限判定 | `AuthorizationService` + `ClientDirectory` | 先认证（客户端登记）后授权（scope）；越权读不泄露资源存在性 |
| 统一编排 | `GatewayService` | 鉴权 → 版本协商 → 幂等执行 → 业务，每步写审计 |
| 统一契约 | `GlobalExceptionHandler` | 固定错误体 `{code, message, requestId}` 与 HTTP 状态映射 |

业务能力为「订单」：`POST /api/orders`（写，强制幂等键）、`GET /api/orders/{id}`（读）。

## 2. 版本协商与弃用规则

客户端通过请求头 `X-Api-Version: major.minor` 声明期望版本（如 `2.0`）。

本地注册表（`orders` 能力）：

| 版本 | 状态 | 含义 |
| --- | --- | --- |
| 0.1 | UNSUPPORTED | 主版本 0 已整体退役，跨主版本互不兼容 |
| 1.0 | DEPRECATED | 已弃用但仍服务，响应带 `Deprecation: true` 与 `Sunset` 提示 |
| 1.2 | SUPPORTED | 受支持的旧主版本，旧客户端不受影响 |
| 2.0 / 3.0 | SUPPORTED | 当前主版本 |

协商规则（顺序即判定优先级）：

1. 缺失头 → `VERSION_MISSING`（400）；
2. 无法解析（如 `v2`）→ `VERSION_UNKNOWN`（404）；
3. 主版本系列完全未知（如 `9.0`）→ `VERSION_UNKNOWN`（404）；
4. 主版本已知但整体退役（如 `0.1`）→ **`VERSION_INCOMPATIBLE`（400）**，表示跨主版本不兼容；
5. 精确命中：选择该版本；DEPRECATED 照常服务并在响应头给出明确弃用/日落信号；
6. 同主版本未精确命中：向前兼容选择 `minor` 不小于声明值的最高注册版本。

响应头：`X-Api-Version-Selected`（实际选中版本）；弃用时附加 `Deprecation`、`Sunset`。
弃用**不影响**仍受支持的旧客户端（如声明 `1.2` 照常成功且无弃用信号）。

## 3. 幂等语义与并发约束

写请求必须携带 `Idempotency-Key`，并对请求体计算指纹（`CreateOrderRequest#fingerprint()`）。

| 结论 `X-Idempotency-Outcome` | 场景 | HTTP |
| --- | --- | --- |
| `EXECUTED` | 首次获得执行权，业务副作用真实发生一次 | 200 |
| `REPLAYED` | 已有成功记录，返回与首次完全一致的结果，无副作用 | 200 |
| `IN_PROGRESS` | 同键请求正在处理中（并发重试），错误信息指明持有请求标识 | 409 `IDEMPOTENCY_IN_PROGRESS` |
| `RETRY_AFTER_FAILURE` | 上次执行失败，同键同负载允许重新执行一次 | 200（成功时） |
| `KEY_CONFLICT` | 同一幂等键携带不同请求负载 | 409 `IDEMPOTENCY_KEY_CONFLICT` |
| —（缺失键） | 写请求未带幂等键 | 400 `IDEMPOTENCY_KEY_MISSING` |

并发正确性保证（见 `IdempotencyService`）：

- 每个幂等键一把独立 `ReentrantLock`：身份判定与状态迁移串行化，不同键互不阻塞；
- 业务动作在**锁外**执行，使并发同键请求能观察到 `IN_PROGRESS` 而非排队重复执行；
- 记录不可变，`IN_PROGRESS → SUCCEEDED/FAILED` 以 CAS（`ConcurrentMap.replace(k,old,new)`）落盘，
  因此不重复执行、结果不被覆盖、状态不丢失；
- `FAILED` 记录在同键同负载重试时移除并重新占位，执行业务恰好再发生一次。

## 4. 权限判定方式

- 认证：`X-Client-Id` 必须在本地客户端目录登记。内置客户端：
  - `client-mobile-1` / `client-legacy-1`：`orders:read` + `orders:write`；
  - `client-readonly-1`：仅 `orders:read`；
  - `client-noaccess-1`：无任何 scope。
- 授权：按资源要求的 scope 判定（写需 `orders:write`，读需 `orders:read`）。
- **不泄露资源存在性**：未认证访问资源统一表现为 401；越权读资源与资源不存在**同构返回 404**，
  真实拒绝原因只写入审计日志（`DENIED_AS_NOT_FOUND:<id>`）。
- 判定顺序：认证先于版本协商与幂等校验（错误版本 + 未认证时先报 401）。

## 5. 错误契约

所有错误响应体固定为：

```json
{ "code": "VERSION_INCOMPATIBLE", "message": "…可解释信息…", "requestId": "…" }
```

| code | HTTP | 触发场景 |
| --- | --- | --- |
| `VERSION_MISSING` | 400 | 缺少 `X-Api-Version` |
| `VERSION_UNKNOWN` | 404 | 版本无法解析或主版本系列未知 |
| `VERSION_INCOMPATIBLE` | 400 | 已知主版本已退役（跨主版本不兼容） |
| `IDEMPOTENCY_KEY_MISSING` | 400 | 写请求缺少幂等键 |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | 同键并发、首请求仍在处理 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 同键不同请求负载 |
| `AUTH_UNAUTHENTICATED` | 401 | 缺失/未登记客户端 |
| `AUTH_FORBIDDEN` | 403 | 已认证但缺少所需 scope（写能力） |
| `RESOURCE_NOT_FOUND` | 404 | 资源不存在/越权读（对外不可区分） |
| `BUSINESS_ERROR` | 422 | 业务规则拒绝（如商品不可下单、数量非正） |
| `INTERNAL` | 500 | 未预期内部错误（不回传堆栈） |

每个响应（含错误）都回写 `X-Request-Id`；客户端可用 `X-Request-Id` 头做关联，缺失时服务端生成。

## 6. 审计与可解释性

`AuditLogger` 为每次决策输出结构化日志并保留进程内轨迹（`AuditEntry`），字段包括：
请求标识、客户端、能力、声明版本、**版本决策原因**、**幂等结果**、**权限判定依据**、最终结果与时间戳。
单测日志中以 `[test-version]`、`[test-idem]`、`[test-auth]`、`[test-contract]` 前缀打印上述四项依据。

## 7. 本地验证方法

```bash
# 编译
mvn -o compile

# 运行全部测试（离线；如需联网首次拉依赖去掉 -o）
mvn -o test

# 只运行某一类测试
mvn -o test -Dtest=VersionNegotiationTest        # 版本不兼容/弃用/旧版本兼容
mvn -o test -Dtest=IdempotencyConcurrencyTest    # 同键并发/重放/失败重试/键冲突/进行中
mvn -o test -Dtest=AuthorizationTest             # 未认证/越权/不泄露存在性
mvn -o test -Dtest=GatewayContractTest           # HTTP 错误契约/响应头/审计

# 启动（随机端口，见 application.properties 的 server.port=0）
mvn -o spring-boot:run
```

手动调用示例：

```bash
# 成功创建（注意记录返回的 X-Request-Id / X-Idempotency-Outcome）
curl -i -X POST http://localhost:<port>/api/orders \
  -H 'X-Api-Version: 2.0' -H 'X-Client-Id: client-mobile-1' \
  -H 'Idempotency-Key: demo-key-1' -H 'Content-Type: application/json' \
  -d '{"product":"widget","quantity":2,"amount":19.99}'

# 重复提交：返回相同响应体，X-Idempotency-Outcome: REPLAYED
curl -i -X POST http://localhost:<port>/api/orders \
  -H 'X-Api-Version: 2.0' -H 'X-Client-Id: client-mobile-1' \
  -H 'Idempotency-Key: demo-key-1' -H 'Content-Type: application/json' \
  -d '{"product":"widget","quantity":2,"amount":19.99}'

# 已弃用版本：200 但带 Deprecation/Sunset
curl -i -X POST http://localhost:<port>/api/orders \
  -H 'X-Api-Version: 1.0' -H 'X-Client-Id: client-legacy-1' \
  -H 'Idempotency-Key: demo-key-2' -H 'Content-Type: application/json' \
  -d '{"product":"gadget","quantity":1,"amount":9.99}'
```

## 8. 测试覆盖一览（27 个用例）

- 版本：缺失、非法格式、未知主版本、退役主版本（INCOMPATIBLE 与 UNKNOWN 可区分）、
  弃用信号、1.2 旧客户端不受影响、2.0/3.0 正常；
- 幂等：16 线程同键栅栏并发（断言 EXECUTED=1、副作用=1、其余 IN_PROGRESS/REPLAYED）、
  成功重放结果一致、失败后同键重试真实执行一次、同键不同负载冲突、进行中状态可观察；
- 权限：缺客户端/未登记（401）、只读客户端写（403）、越权读与不存在同为 404、认证先于版本；
- HTTP 契约：五类错误的 code/HTTP 可区分、成功/弃用响应头、串行重放、409 两类冲突、
  422 业务异常、404 同构、审计四要素齐全。
