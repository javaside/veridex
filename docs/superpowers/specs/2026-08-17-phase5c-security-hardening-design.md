# Phase 5-c 安全加固设计文档

> 日期：2026-08-17
> 状态：待用户审阅
> 范围：Phase 5 Enterprise Pilot Readiness 的安全加固

## 1. 背景与目标

Phase 5-a 已提供 scoped API key、Request ID、限流与 OpenAPI；Phase 5-b 已提供 Prometheus、OTel/Tempo、告警和受控 trace-body。当前应用仍存在 5-c 必须处理的安全暴露：Actuator 与业务端口共用、运行配置包含本地默认凭据、上传和解析资源预算不足、对象授权/缓存/出站策略需要系统化回归验证。

目标是在不改变现有业务 API 和 5-a/5-b 契约的前提下，使企业试点具备可验证的安全基线：

- 管理端点绑定独立管理端口，业务端口不暴露管理面；
- 生产启动不接受可用的本地默认凭据或缺失的关键 secret；
- 上传、压缩展开和文档解析受大小、类型、时间、内存和路径边界保护；
- CORS、CSRF、Session cookie、安全响应头和错误响应符合部署边界；
- 对象级授权、缓存隔离、过期 URL、提示注入和出站访问有回归测试；
- 安全策略配置错误 fail-fast，安全组件异常不降级为放行；
- 诊断、日志、审计和错误响应不泄露凭据、正文、原始异常或内部路径。

## 2. 范围与非目标

### 2.1 本阶段范围

1. 管理面隔离：独立 `management.server.port`，端点白名单为 `health`、`info`、`prometheus`，管理端口只绑定内部/受限接口；Prometheus 改抓管理端口。
2. 配置与 Web 安全：生产 profile 的凭据校验、外部化 secret、CORS allowlist、Session cookie 属性、CSRF/API 认证边界、CSP 与其他安全响应头、敏感配置脱敏。
3. 上传与解析沙箱：文件大小、扩展名、MIME 与魔数一致性，压缩条目/展开大小/嵌套深度限制，解析超时和临时目录隔离，拒绝路径穿越与任意网络/文件系统访问。
4. 访问与出站安全：知识对象、预览、下载、trace-body、缓存键和过期 URL 的授权矩阵；模型/解析器出站域名白名单、私网/环回/metadata 地址和重定向防护；检索内容中的指令不得改变系统提示、权限或出站策略。
5. 安全验证：真实数据库、对象存储和消息基础设施上的授权/生命周期测试，恶意文件与资源耗尽样本测试，Compose 和配置静态检查。

### 2.2 非目标

- 不引入 WAF、Alertmanager、集中式 secrets manager 或多租户隔离；
- 不在本阶段实现容器镜像、Kubernetes、Helm 和网络策略，它们属于 Phase 5-d；
- 不重做既有 IAM、API key scope、trace-body 数据模型或观测业务语义；
- 不把应用层白名单误认为部署层网络隔离，部署层仍需由 5-d 和企业网络配置完成；
- 不允许通过“关闭安全检查”作为生产兼容方案。

## 3. 架构与模块边界

### 3.1 管理端口

Spring Boot 管理端点使用独立端口和绑定地址配置。业务端口不暴露 `/actuator/**`；管理端口只暴露健康探针、基础信息和 Prometheus。管理端口默认绑定本机或内部接口，生产配置必须显式提供受限绑定地址和端口。5-b 的 Prometheus job、README、架构文档和 acceptance 命令同步更新为管理端口。

Actuator 不暴露 `env`、`configprops`、`beans`、`mappings`、`loggers`、`heapdump` 或其他诊断端点。敏感配置不进入响应、错误页或日志。

### 3.2 凭据与 Web 安全

数据库、RabbitMQ、MinIO、OpenSearch、Session 签名/加密 key、trace-body key 和外部模型凭据统一从环境变量或部署 secret 注入。开发环境可继续使用明确标记的 local profile，但生产/试点 profile 启动时拒绝空值、已知本地默认值和不满足最小长度/格式的 secret。

安全配置由单一 properties/configuration 边界校验，避免各模块自行解释环境变量。CORS 只接受显式 allowlist；禁止通配来源配合 credentials。Session cookie 设置 `HttpOnly`、`Secure`（非 local profile）、适当 `SameSite` 和受限 path。浏览器 Session 的写操作保留 CSRF 防护；Bearer API key 请求使用明确的无状态边界，不通过关闭全局 CSRF 来规避浏览器防护。

响应头至少包括 CSP、`X-Content-Type-Options: nosniff`、`Referrer-Policy`、`Permissions-Policy` 和合理的 frame 防护。错误响应使用固定错误码，不返回异常 message、堆栈、磁盘路径、对象 key、外部 URL 或认证细节。

### 3.3 上传与解析沙箱

上传入口和异步 worker 共同执行安全边界，不能只依赖 HTTP multipart 限制。配置包含：最大原始文件字节数、允许的文档类型、压缩最大展开字节数、最大条目数、最大嵌套深度、单阶段解析超时、临时目录和可选内存预算；每项有保守默认值、可配置值和硬上限。超过硬上限或配置非法时启动失败。

文件处理顺序固定为：认证与对象授权、大小限制、扩展名/MIME/魔数校验、压缩结构预算、隔离临时文件、受限解析、内容写回。临时文件使用不可预测的任务目录并在成功、失败和取消路径清理。解析器接口不提供任意网络客户端或任意路径写入能力；外部链接、嵌入资源和宏/脚本按拒绝处理。解析异常转换为固定阶段和错误码，原始异常仅保存在受保护的内部日志上下文中且不得包含正文。

解析失败只使当前 `DocumentVersion` 失败，旧的已发布 release 保持可查询；状态更新和审计仍必须完成。

### 3.4 授权、缓存、提示注入与出站

所有对象读取路径按“用户、知识库 scope、文档/版本状态、资源动作”执行服务端授权；不能用 URL 中的 UUID 判断权限。不存在和无权资源使用不泄露存在性的响应。缓存键必须包含授权主体和有效 scope，敏感预览/trace-body 响应使用 `no-store` 或等价策略；撤销权限、文档下线和 URL 过期后缓存不得继续返回内容。

检索文档和 chunk 是不可信数据。系统提示、工具权限、知识范围、出站白名单和审计策略只能由服务端配置决定；文档中的“忽略规则”“调用 URL”“泄露提示”等文本只能作为证据内容，不能改变控制流。测试使用唯一 sentinel 验证 prompt injection 不会扩大知识范围、调用工具或触发网络访问。

模型和解析器出站请求必须经过统一策略：仅允许配置的 scheme、host、port 和路径范围；解析 DNS 后拒绝 loopback、link-local、RFC1918、IPv6 本地/保留和云 metadata 地址；禁止自动跟随未经重新校验的重定向，并限制响应大小和连接/读取时间。策略拒绝使用固定错误码并记录 requestId 和阶段，不记录完整 URL 查询参数或凭据。

## 4. 配置矩阵与失败处理

| 配置类别 | 规则 | 失败行为 |
|---|---|---|
| 管理端口/绑定地址 | 独立端口；生产必须显式受限绑定 | 启动失败；业务端口不提供 Actuator |
| Actuator exposure | 仅 `health,info,prometheus` | 配置包含敏感端点时启动失败或校验失败 |
| 数据库/消息/对象存储凭据 | 生产不得为空或使用 local 默认值 | 启动失败 |
| Session/trace/model secret | 外部注入，满足长度和格式要求 | 启动失败；不回显值 |
| CORS allowlist | 显式来源；credentials 禁止 `*` | 启动失败 |
| 上传/解析预算 | 不超过硬上限，时间和大小为正数 | 启动失败 |
| 出站 allowlist | scheme/host/port 明确，禁止危险地址 | 启动失败或请求拒绝 |
| 运行时安全组件 | 授权、路径、DNS、缓存检查异常 | fail closed，业务返回固定错误码 |

安全策略异常不得变成匿名放行。上传格式/预算失败、出站拒绝和对象越权返回稳定错误码；权限不存在性不通过响应差异泄漏。认证入口遵循现有 Session/API key 语义。安全审计只记录固定 action、result、resource type、requestId 和必要的 actor 信息。

## 5. 测试策略

### 5.1 单元与配置测试

- 管理端口和端点 exposure 默认值、生产配置拒绝 local 默认凭据；
- CORS、cookie、CSRF、响应安全头和错误脱敏；
- 文件类型/magic bytes、压缩预算、路径规范化、临时目录清理；
- 出站 URL scheme/host/port/DNS/重定向判断；
- 缓存键含授权主体，过期/撤销后失效；
- 提示注入输入不会改变服务端策略；
- 固定错误码映射不含异常 message、路径、正文或凭据。

### 5.2 集成与安全回归

- 应用端口访问 `/actuator/**` 被拒绝，管理端口只返回允许端点；Prometheus 能从管理端口抓取；
- PLATFORM_ADMIN、KNOWLEDGE_ADMIN、EMPLOYEE、API key 和匿名身份覆盖知识、预览、下载、trace-body、缓存和过期 URL 矩阵；
- 真实 MinIO/PostgreSQL/RabbitMQ 流程覆盖超限文件、扩展名/MIME 冲突、恶意压缩包、嵌套压缩、路径穿越、解析超时和 worker 重启；
- 失败新版本不会替换旧 release；临时对象和解析目录在所有终态清理；
- prompt injection sentinel 不改变检索 scope、系统提示或外部请求；
- 出站请求覆盖 loopback、私网、link-local、metadata、DNS 重绑定、恶意重定向、超大响应和超时；
- 安全响应和日志扫描不含密码、token、cookie、正文、磁盘路径、原始异常和完整 query string。

### 5.3 部署与最终门禁

- `./scripts/verify.sh`；
- Compose 配置、管理端口、Prometheus target 和安全配置静态校验；
- 若 Docker 可用，启动栈后验证业务端口/管理端口端点差异；
- 后端安全回归、架构测试、前端测试和构建；
- `git diff --check`。

## 6. 验收标准

Phase 5-c 完成需要全部满足：

1. Actuator 仅在独立管理端口暴露允许端点，业务端口无法访问；
2. Prometheus 从管理端口抓取成功，5-b 观测链路不退化；
3. 生产/试点启动拒绝缺失、空值和本地默认凭据；敏感配置不可通过 Actuator 或错误响应读取；
4. CORS、CSRF、Session cookie 和安全响应头符合配置矩阵；
5. 上传和解析执行类型、大小、展开、条目、嵌套、时间、内存和临时目录限制；
6. 恶意文件、路径穿越和资源耗尽不会导致任意文件写入、网络访问、进程崩溃或旧 release 下线；
7. 对象授权、权限撤销、缓存隔离、预览/下载/trace-body 和过期 URL 测试零越权；
8. 检索内容中的提示注入不能改变系统策略、知识范围、工具权限或出站访问；
9. 出站 allowlist 拒绝危险地址、重绑定、恶意重定向和超限响应；
10. 安全策略异常 fail closed，业务错误不泄露敏感数据；
11. 5-a、5-b 回归测试、架构测试、部署校验和 `git diff --check` 全部通过。

## 7. 固定决策

- 本阶段采用完整 5-c 范围，不拆出独立的上传安全子项目；
- Actuator 采用独立管理端口；
- 生产/试点配置必须外部化关键 secret，并拒绝 local 默认值；
- 上传/解析限制在入口和异步 worker 双重执行；
- 对象授权、缓存、提示注入和出站策略均以服务端策略为准；
- 安全检查失败采用 fail closed；业务错误固定码、最小泄露；
- Phase 5-d 负责容器、Kubernetes、Helm 和部署网络策略，不在本阶段实现。
