# Phase 5-a API 治理 — 设计文档

> 日期：2026-08-16
> 状态：待用户确认

## 1. 背景与目标

### 1.1 现状

Phase 4 完成后，平台具备完整的知识入库、带权限问答与评测闭环，但 API 层仍是「裸 Session」状态：

| 项 | 现状 | 问题 |
|---|---|---|
| 认证 | 仅 Session（formLogin JSON），CSRF disabled | 无程序化访问凭据；企业集成（脚本/网关/其他系统）无法安全接入 |
| 限流 | 无 | 任何登录用户可无限速调用检索/生成等高成本端点 |
| Request ID | `audit_event.request_id` 列存在但恒为 null；`AuditRecorder.record(..., requestId, ...)` 有参数无调用方传值 | 故障排查无法串联一次请求的多条日志 |
| API 文档 | 无 | 外部集成方只能读源码 |
| `/actuator/**` | permitAll | 泄漏健康细节与指标（Phase 5-b 再收紧） |

### 1.2 本轮目标（Phase 5 子项目 a）

建立 API 治理基础：**scoped API key（可吊销）+ 全局限流 + Request ID 透传 + OpenAPI 文档 + 前端 key 管理页**。

### 1.3 范围

- 做：API key 域模型与 REST API、key 认证与鉴权、固定窗口限流、Request ID 过滤器、springdoc OpenAPI、`/admin` 管理页。
- 不做：OTel tracing、Grafana、告警（5-b）；K8s/Helm、镜像（5-d）；分布式限流（Redis）——单节点试点够用，留到规模化再议；actuator 收紧（5-b，需先有 metrics 拉取方案再收紧）。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|---|---|
| D1 | **Key 作用域按 API 命名空间** | scope 枚举 `qa / knowledge:read / knowledge:write / configuration / evaluation / feedback`；请求路径映射到 scope，未匹配的端点（含管理端点 `/api/iam/keys`）**仅限 Session** 访问，key 不可管理 key。 |
| D2 | **内存固定窗口限流** | 每用户（Session 与 key 统一按 userId）滑动计数，默认 600 req/min，超限 `429 + Retry-After`；单机内存实现，零新依赖。 |
| D3 | **含前端管理页** | `/admin` 从 ComingSoon 占位换为真实页面：key 列表、创建对话框（token 明文只显示一次）、吊销按钮。 |
| D4 | **Token 格式与服务端存储** | `vd_` + 43 字符 URL-safe 随机（32 字节熵）；库中只存 SHA-256 哈希；前 8 字符明文前缀存列用于列表识别。 |
| D5 | **key 认证复用现有 principal 链** | `ApiKeyAuthFilter` 认证成功后构造 `PlatformUserDetails`（从库加载用户），所有现有 controller 权限逻辑零改动。 |
| D6 | **限流与 Request ID 放 `shared` 模块** | 跨切面基础设施归属 shared（与 outbox/messaging 同级），不进任何业务模块。 |
| D7 | **springdoc v3.1.0** | v3 系列是唯一支持 Spring Boot 4 的版本线（Maven Central 确认 `3.1.0` 为最新 release）；项目当前 Boot 4.0.0。 |
| D8 | **`/v3/api-docs` 与 swagger-ui 仅限 Session** | API 文档含内部端点结构，不匿名公开；key 也不可访问（避免泄漏全量路径）。 |
| D9 | **迁移 V12** | `V12__api_key.sql` 新建 `api_key` 表。 |
| D10 | **权限** | key 管理是平台管理动作：`PLATFORM_ADMIN` 全量；`KNOWLEDGE_ADMIN` 仅能为自己创建/吊销。 |

## 3. Scope 与路径映射

| Scope | 允许的路径前缀 | 对应能力 |
|---|---|---|
| `qa` | `/api/qa/**`、`/api/conversations/**` | 问答与会话 |
| `knowledge:read` | GET `/api/knowledge-bases/**`、GET `/api/documents/**` | 知识浏览、预览 |
| `knowledge:write` | 非 GET `/api/knowledge-bases/**`、非 GET `/api/documents/**` | 建库、上传、发布 |
| `configuration` | `/api/configuration/**` | 配置版本读写 |
| `evaluation` | `/api/evaluation/**` | 评测集与运行 |
| `feedback` | `/api/feedback/**` | 反馈提交与查询 |

规则：

- 请求头 `Authorization: Bearer vd_...` → key 认证路径；scope 不匹配 → `403`。
- 未匹配任何 scope 的路径（含 `/api/iam/keys`、`/v3/api-docs`、swagger-ui、actuator）→ key 请求一律 `403`，Session 正常。
- key 持有者仍受用户角色约束（EMPLOYEE 的 key 无法调 evaluation——evaluation controller 已有 `requireAdmin`）。
- Session 与 key 同请求时以 Session 为准（浏览器调试场景）。

## 4. 领域模型

```text
PlatformUser 1 ──── * ApiKey
```

`ApiKey`（`iam.domain`）：id、userId、name（展示名）、`tokenHash`（SHA-256 hex，唯一索引）、`tokenPrefix`（前 8 字符）、scopes（逗号分隔存储）、`revokedAt`（可空）、createdAt、lastUsedAt（可空，认证时惰性更新）。

展示态只暴露 `id / name / tokenPrefix / scopes / createdAt / revokedAt / lastUsedAt`，**永不**返回 tokenHash 或明文。

## 5. 数据模型（V12__api_key.sql）

```sql
CREATE TABLE api_key (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(200) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,      -- SHA-256 hex
    token_prefix VARCHAR(16) NOT NULL,        -- 明文前 8 字符，如 'vd_1a2b3c4'
    scopes VARCHAR(500) NOT NULL,             -- 逗号分隔，如 'qa,knowledge:read'
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_api_key_user ON api_key (user_id);
```

## 6. API（iam/api）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/api/iam/keys` | 创建 key `{name, scopes[]}` → 返回 `id + token 明文（仅此一次）` | PLATFORM_ADMIN（任意用户）/ KNOWLEDGE_ADMIN（仅自己） |
| GET | `/api/iam/keys` | 当前用户可见的 key 列表（admin 见全部） | 同上 |
| DELETE | `/api/iam/keys/{id}` | 吊销（置 revoked_at，不物理删） | 同上 |

创建响应 DTO `ApiKeyCreated(id, name, token, scopes, createdAt)`；列表 DTO `ApiKeyView(id, name, tokenPrefix, scopes, createdAt, revokedAt, lastUsedAt)`。

## 7. 认证与鉴权链

```text
请求 → ApiKeyAuthFilter（Bearer vd_ 开头才处理）
         ├─ 查 tokenHash → 无/已吊销 → 401 JSON（不走 formLogin 302）
         ├─ 用户 disabled → 401
         └─ 命中 → SecurityContext 放 PlatformUserDetails + ApiKeyAuthToken
       → AuthorizationFilter / authorizeHttpRequests
         ├─ Session 已认证 → 走原逻辑（key 忽略）
         ├─ key 认证 → ScopeAuthorizationManager 按 §3 映射判定
         └─ 匿名 → 302 登录（现状不变）
```

- `ApiKeyAuthFilter` 加在 `UsernamePasswordAuthenticationFilter` 之前。
- scope 判定失败返回 403 JSON（body `{"error":"insufficient_scope"}`），不进 controller。
- lastUsedAt 更新走异步（简单 `@Async` 或直接同步更新，单节点试点规模下同步可接受——实现取同步，避免引入线程池配置）。

## 8. 限流（shared/infrastructure）

- `RateLimitFilter`（order 在认证之后）：按 `CurrentActor.id()` 计数。
- 算法：固定窗口，key = `userId + 当前分钟`，`ConcurrentHashMap<Long, Map<UUID, AtomicLong>>`，整分钟过期清理。
- 超限响应：`429`，header `Retry-After: <到下一分钟的秒数>`，body `{"error":"rate_limited"}`。
- 阈值配置 `veridex.api.rate-limit-per-minute`（默认 600，0 = 关闭），actuator 端点不计入（permitAll 路径早于业务过滤器短路）。
- SSE（`/api/qa/ask`）连接建立计一次，不按事件计。

## 9. Request ID（shared/infrastructure）

- `RequestIdFilter`（最外层）：读 `X-Request-Id`（无则生成 UUID），写回响应头，放 `RequestContextFilter` 同款 `request.setAttribute("veridex.requestId", ...)`。
- `MDC.put("requestId", ...)` + finally remove，日志格式追加 `[%X{requestId}]`。
- `AuditRecorder` 调用方（knowledge/ingestion 现有调用点）补传 requestId。
- Spring AI 观测（`log-prompt: false`）保持现状，不动。

## 10. OpenAPI（springdoc v3.1.0）

- 依赖 `org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.0`（不要 ui starter，前端自带控制台，避免多一个暴露面）。
- 端点：`/v3/api-docs`（JSON）。默认扫描全部 `/api/**` controller。
- 配置：`springdoc.paths-to-match=/api/**`、`springdoc.default-support-form-authentication=false`。
- 权限：`SecurityConfig` 增加 `.requestMatchers("/v3/api-docs/**").hasAnyRole(PLATFORM_ADMIN, KNOWLEDGE_ADMIN)`。
- 版本验证步骤写进计划：若 v3.1.0 与 Boot 4.0.0 出现兼容问题（Jackson 3 `tools.jackson`），降级方案是手写 `OpenApiResource` 只输出骨架——但先验证官方 starter。

## 11. 前端（/admin）

- 路由 `/admin` 内容从 `ComingSoonPage` 换为 `ApiKeyAdminPage`。
- 页面结构（沿用现有 panel/card 风格）：
  - key 列表表格：名称、前缀、scopes、创建时间、最近使用、状态（active/revoked）、吊销按钮；
  - 「创建 API key」按钮 → 对话框：名称 + scopes 多选（`qa` 等 6 项）→ 提交后**一次性显示完整 token**（提示「关闭后无法再查看」+ 复制按钮）；
  - 仅 KNOWLEDGE_ADMIN 且非 admin 时不可见他人 key（列表接口对 admin 返回全部、对 kadmin 只返回自己的）。
- 前端 API 层：`web/src/features/admin/adminApi.ts`（list/create/revoke）。
- 测试：Vitest 覆盖列表渲染、创建对话框 token 一次性显示、吊销交互。

## 12. 测试

- **服务单测**（Mockito）：创建 key（生成 vd_ 前缀、哈希落库）、吊销后认证 401、scope 解析。
- **集成测试**（`PostgresIntegrationTest` + RestTestClient）：
  - key 创建 → Bearer 调 `/api/qa/ask`（scope=qa）成功；
  - scope 不匹配（key 只有 qa，调 `/api/evaluation/datasets`）→ 403；
  - 已吊销 key → 401；
  - 无 Authorization 头 → 302（现状不变）；
  - 限流：`veridex.api.rate-limit-per-minute=3` 的测试 profile 下第 4 次请求 429 + Retry-After；
  - Request ID：响应头含 `X-Request-Id`；带自定义 `X-Request-Id` 透传；
  - `/v3/api-docs`：admin session 200、匿名 401/302。
- **ArchitectureTest**：shared 新增 infrastructure 类不破坏模块边界；iam 依赖不变。
- **DatabaseMigrationTest**：V12 断言 `api_key` 表存在。

## 13. 验收标准

1. PLATFORM_ADMIN 能创建/查看/吊销 API key，创建时 token 明文只出现一次。
2. Bearer key 按 scope 访问对应命名空间；越权 scope 与未映射路径均 403。
3. 吊销立即生效（后续请求 401）。
4. 超过阈值的请求收到 429 + Retry-After。
5. 所有响应带 `X-Request-Id`，日志可按 requestId 检索。
6. 登录管理员可访问 `/v3/api-docs` 获取 OpenAPI JSON。
7. `/admin` 页面可完成 key 全生命周期管理。
8. `./scripts/verify.sh` 全绿。
