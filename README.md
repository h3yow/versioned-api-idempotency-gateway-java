# 多版本 API 兼容与幂等网关（本地实现）

面向多版本客户端的本地 API 兼容与幂等处理能力：**版本协商、重复提交、权限判定、异常返回**
在所有调用入口（HTTP 端点或测试直连）下保持一致且可解释。纯本地实现，不依赖外部服务或真实凭据
（客户端目录与版本表均为进程内登记，`server.port=0` 随机端口启动）。

## 1. 能力总览

| 能力 | 入口/组件 | 说明 |
| --- | --- | --- |
| 版本协商 | `VersionNegotiator` + `VersionRegistry` | 按 `X-Api-Version` 选择兼容处理路径，拒绝原因稳定可区分，弃用版本给信号但继续服务 |
| 版本化返回 | `OrderResponsePresenter` + `order/view/*` | 不同主版本真正返回不同结构（1.x 精简 / 2.x、3.x 扩展），客户端可直接看出差异 |
| 幂等处理 | `IdempotencyService` + `IdempotencyStore` | 按 `Idempotency-Key` 去重；每键一把锁 + 终态 CAS，同键并发只产生一次副作用 |
| 有界保留 | `IdempotencyStore` + `IdempotencyMaintenance` | 终态记录按 TTL 与容量上限清理；处理中记录绝不清理；清理与并发提交互不破坏 |
| 权限判定 | `AuthorizationService` + `ClientDirectory` | 先认证（客户端登记）后授权（scope）；越权读不泄露资源存在性 |
| 统一编排 | `GatewayService` | 鉴权 → 版本协商 → 幂等执行 → 业务，每步写审计 |
| 统一调用入口 | `POST /api/internal/invoke`（`UnifiedController`） | 一次提交能力/操作/参数，与业务端点共用同一套鉴权、版本、幂等、异常返回 |
| 统一契约 | `GlobalExceptionHandler` | 固定错误体 `{code, message, requestId}` 与 HTTP 状态映射 |

业务能力为「订单」：`POST /api/orders`（写，强制幂等键）、`GET /api/orders/{id}`（读）；
内部还提供统一入口 `POST /api/internal/invoke`（写操作 `orders/createOrder`、读操作 `orders/getOrder`）。

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
6. **同主版本未精确命中（声明了一个没有单独登记的次版本，如 `1.1`、`2.5`）：不拒绝**，
   回落到该主版本内**最高的可服务注册版本**（1.1 → 1.2、2.5 → 2.0），按该主版本的处理方式返回。

响应头：`X-Api-Version-Selected`（实际选中版本）；弃用时附加 `Deprecation`、`Sunset`。
弃用**不影响**仍受支持的旧客户端（如声明 `1.2` 照常成功且无弃用信号）。

### 2.1 各主版本返回形态差异与兼容范围

版本协商只决定“用哪套处理方式”，最终 JSON 由 `OrderResponsePresenter` 按选中主版本渲染。
同一份订单结果在不同主版本下结构确实不同，客户端可直接区分：

| 主版本 | 视图类型 | 字段 | 兼容范围 |
| --- | --- | --- | --- |
| 1.x（含 1.0 弃用、1.2） | `OrderViewV1` | `orderId, product, quantity, status`（**精简**，4 个） | 声明 1.0/1.2 精确命中；声明 1.x 内任意未登记次版本（1.1、1.9…）回落到 1.2，仍是精简结构 |
| 2.x | `OrderViewV2` | 在 1.x 基础上增加 `amount, createdAt, serverTraceId, apiVersion`（**扩展**，8 个） | 声明 2.0 精确命中；声明 2.x 内未登记次版本（2.1、2.5…）回落到 2.0，仍是扩展结构 |
| 3.x | `OrderViewV3` | 在 2.x 基础上再增加 `features`（**进一步扩展**，9 个） | 声明 3.0 精确命中；同主版本回落同理 |

要点：

- 旧主版本（1.x）**保留精简结构**，当前主版本（2.x/3.x）返回**带扩展信息**的结构；
- 同主版本内的次版本兼容**只在该主版本内发生**，绝不跨主版本降级/升级（声明 2.x 不会落到 1.x，反之亦然）；
- 已弃用的 1.0 照常返回成功（精简结构）+ `Deprecation: true` + `Sunset` 提示；
- `Sunset` 头值使用合规 ASCII 日期（RFC 8594），避免被真实容器丢弃；
- 渲染是纯函数（只依赖订单内容与选中版本），所以业务端点、统一入口、重放请求三者结果一致。

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

### 3.1 有界保留与过期清理（TTL / 容量）

记录不再只进不出。仅**终态**（SUCCEEDED/FAILED）记录参与保留策略，**IN_PROGRESS 永不清理**：

| 配置项 | 默认 | 含义 |
| --- | --- | --- |
| `gateway.idempotency.ttl-ms` | `3600000`（1 小时） | 终态记录存活时间，从**完成时刻**起算；`<=0` 关闭 TTL |
| `gateway.idempotency.max-records` | `10000` | 终态记录容量上限；超出时淘汰**最旧终态**；`<=0` 不限 |
| `gateway.idempotency.cleanup-interval-ms` | `60000`（1 分钟） | 定时清理间隔（`IdempotencyMaintenance`） |

过期与容量行为：

- **惰性过期**：请求到达某个键时，若其终态记录已过 TTL，在该键锁内二次确认后删除，
  该键被**当成新请求处理**（结论 `EXECUTED`，可重新执行、也可换不同负载，不再报键冲突）；
- **定时清理**：`IdempotencyMaintenance` 周期性扫描，删除过 TTL 的终态记录，并按容量淘汰最旧终态；
- **容量淘汰**：仅淘汰终态记录（按完成时间、再按键名稳定排序取最旧），处理中记录不参与；
- TTL 内仍 `REPLAYED`，结论与首次一致。

与并发提交同时发生时的安全性（见 `IdempotencyStore`）：

- 使用固定数量的**条带锁**（256 条带），同一键恒定映射到同一条带；条带集合天然有界，
  不存在“删掉键锁后新旧两把锁并存”的竞态；
- 所有清理删除都在该键条带锁内做 **二次确认 + 条件删除**（`ConcurrentMap.remove(k, expected)`）：
  只有记录仍是同一引用、仍满足过期条件才删除；
- 因此清理不会让同一键被执行两次、不会覆盖结果或丢失状态；
- **正在处理中的记录绝不删除**，并发请求稳定观察到 `IN_PROGRESS`，不会拿到错误结论。

## 3.5 内部统一调用入口 `POST /api/internal/invoke`

内部服务无需按业务接口逐个对接，一次提交「能力 + 操作 + 参数」：

```json
{
  "capability": "orders",
  "operation": "createOrder",
  "params": { "product": "widget", "quantity": 2, "amount": 19.99 }
}
```

| capability | operation | params | 类型 | 幂等键 |
| --- | --- | --- | --- | --- |
| `orders` | `createOrder` | `product` / `quantity` / `amount` | 写 | 必须 |
| `orders` | `getOrder` | `orderId` | 读 | 不需要 |

与业务端点**完全共用同一套**鉴权、版本协商、幂等、异常返回与响应头：

- 同样的输入，两边 HTTP 状态、错误 `code`、`X-Api-Version-Selected`、`Deprecation/Sunset`、
  `X-Idempotency-Outcome` 与响应体都对得上（统一入口从 params 重建出**同一个** `CreateOrderRequest`，
  因此请求负载指纹一致，幂等记录也**共享**）；
- 未授权（401/403）、版本不兼容（400/404）、重复提交（`REPLAYED`/`IN_PROGRESS`/`KEY_CONFLICT`）
  在两个入口表现一致；
- **认证先于能力/操作识别**：未认证时即使能力/操作未知也统一 401，不暴露能力或资源是否存在；
  未授权读资源与资源不存在同为 404；
- 未知能力 → `CAPABILITY_UNKNOWN`（404），未知操作 → `OPERATION_UNKNOWN`（404），
  请求体/参数非法 → `INVOKE_REQUEST_INVALID`（400）；
- 版本形态同样由选中主版本渲染（1.x 精简、2.x/3.x 扩展）。

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
| `CAPABILITY_UNKNOWN` | 404 | 统一入口：未知能力（已认证之后） |
| `OPERATION_UNKNOWN` | 404 | 统一入口：该能力下未知操作 |
| `INVOKE_REQUEST_INVALID` | 400 | 统一入口：请求体/必填参数缺失或类型非法 |
| `BUSINESS_ERROR` | 422 | 业务规则拒绝（如商品不可下单、数量非正） |
| `INTERNAL` | 500 | 未预期内部错误（不回传堆栈） |

每个响应（含错误）都回写 `X-Request-Id`；客户端可用 `X-Request-Id` 头做关联，缺失时服务端生成。

## 6. 审计与可解释性

`AuditLogger` 为每次决策输出结构化日志并保留进程内轨迹（`AuditEntry`），字段包括：
请求标识、客户端、能力、声明版本、**版本决策原因**、**幂等结果**、**权限判定依据**、最终结果与时间戳。
单测日志中以 `[test-version]`、`[test-version-shape]`、`[test-idem]`、`[test-retention]`、
`[test-auth]`、`[test-contract]`、`[test-parity]` 前缀打印上述依据（详见第 8 节）。

## 7. 本地验证方法

```bash
# 编译
mvn -o compile

# 运行全部测试（离线；如需联网首次拉依赖去掉 -o）
mvn -o test

# 只运行某一类测试
mvn -o test -Dtest=VersionNegotiationTest        # 版本不兼容/弃用/旧版本兼容
mvn -o test -Dtest=VersionShapeTest              # 主版本差异化返回 / 同主版本未登记次版本兼容落点
mvn -o test -Dtest=IdempotencyConcurrencyTest    # 同键并发/重放/失败重试/键冲突/进行中
mvn -o test -Dtest=IdempotencyRetentionTest      # TTL 过期/容量淘汰/清理与并发同时发生
mvn -o test -Dtest=AuthorizationTest             # 未认证/越权/不泄露存在性
mvn -o test -Dtest=GatewayContractTest           # HTTP 错误契约/响应头/审计
mvn -o test -Dtest=UnifiedEntryParityTest        # 两个入口一致性（真实 HTTP + 真实并发）

# 调整幂等保留策略后验证（application.properties 或命令行覆盖）
mvn -o spring-boot:run -Dspring-boot.run.arguments=--gateway.idempotency.ttl-ms=2000,--gateway.idempotency.max-records=50

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

# 统一调用入口（与上面业务端点等价；用相同 Idempotency-Key 会得到 REPLAYED 与一致响应体）
curl -i -X POST http://localhost:<port>/api/internal/invoke \
  -H 'X-Api-Version: 2.0' -H 'X-Client-Id: client-mobile-1' \
  -H 'Idempotency-Key: demo-key-1' -H 'Content-Type: application/json' \
  -d '{"capability":"orders","operation":"createOrder","params":{"product":"widget","quantity":2,"amount":19.99}}'

# 不同主版本返回结构对比：1.2 仅 4 字段；3.0 含 amount/createdAt/serverTraceId/apiVersion/features
curl -s -X POST http://localhost:<port>/api/internal/invoke \
  -H 'X-Api-Version: 1.2' -H 'X-Client-Id: client-legacy-1' \
  -H 'Idempotency-Key: demo-v1' -H 'Content-Type: application/json' \
  -d '{"capability":"orders","operation":"createOrder","params":{"product":"widget","quantity":1,"amount":9.99}}'
curl -s -X POST http://localhost:<port>/api/internal/invoke \
  -H 'X-Api-Version: 3.0' -H 'X-Client-Id: client-mobile-1' \
  -H 'Idempotency-Key: demo-v3' -H 'Content-Type: application/json' \
  -d '{"capability":"orders","operation":"createOrder","params":{"product":"widget","quantity":1,"amount":9.99}}'
```

## 8. 测试覆盖一览（44 个用例）

- 版本（`VersionNegotiationTest`，8）：缺失、非法格式、未知主版本、退役主版本（INCOMPATIBLE 与 UNKNOWN 可区分）、
  弃用信号、1.2 旧客户端不受影响、2.0/3.0 正常；
- 版本形态（`VersionShapeTest`，5）：1.x 精简（4 字段）/2.x 扩展（8 字段）/3.x 再扩展（9 字段）、
  同主版本未登记次版本（1.1→1.2、2.5→2.0）兼容落点且形态正确、弃用 1.0 精简结构 + 弃用信号；
- 幂等（`IdempotencyConcurrencyTest`，5）：16 线程同键栅栏并发（断言 EXECUTED=1、副作用=1、其余 IN_PROGRESS/REPLAYED）、
  成功重放结果一致、失败后同键重试真实执行一次、同键不同负载冲突、进行中状态可观察；
- 幂等保留（`IdempotencyRetentionTest`，5，可注入时钟）：TTL 内重放/过期后重新执行、过期后可换负载、
  IN_PROGRESS 绝不过期、容量淘汰最旧终态、TTL 清理与同键并发提交同时发生（恰好执行一次、结果不被覆盖、状态不丢失）；
- 权限（`AuthorizationTest`，5）：缺客户端/未登记（401）、只读客户端写（403）、越权读与不存在同为 404、认证先于版本；
- HTTP 契约（`GatewayContractTest`，8）：五类错误的 code/HTTP 可区分、成功/弃用响应头、串行重放、409 两类冲突、
  422 业务异常、404 同构、审计四要素齐全；
- 两入口一致性（`UnifiedEntryParityTest`，7，真实 HTTP 随机端口 + 真实并发）：
  正常（1.x/2.x/3.x 形态逐项一致）、弃用、版本三类拒绝、鉴权 401/403（含未认证不暴露未知能力）、
  缺幂等键/业务拒绝、读路径 404 同构与正常读取一致、**同一幂等键在两入口 24 线程混合并发**
  （全局 EXECUTED 恰好 1、副作用 1、两入口都观察到 IN_PROGRESS/REPLAYED、并发后两入口串行重放结果一致）。

> 日志前缀：`[test-version]`、`[test-version-shape]`、`[test-idem]`、`[test-retention]`、
> `[test-auth]`、`[test-contract]`、`[test-parity]`，运行 `mvn -o test` 可直接看出覆盖的场景、
> 每个用例的输入与结论。
