# Phase 5-d 容器、Kubernetes 与 Helm 设计文档

> 日期：2026-08-17
> 状态：待用户审阅
> 范围：Phase 5 Enterprise Pilot Readiness 的部署与受限网络安装能力

## 1. 背景与目标

Phase 5-a 已完成 API 治理，Phase 5-b 已完成可观测性，Phase 5-c 已完成应用安全加固。当前 Veridex 仍以宿主机 Java 进程和 Vite 开发服务器运行：仓库没有应用 Dockerfile、Kubernetes manifests 或 Helm chart，Compose 只运行基础设施与观测组件，Prometheus 仍假设应用运行在宿主机。

Phase 5-d 的目标是把现有应用变成可重复构建、可审计、可在普通与受限网络企业环境安装的部署单元：

- 分别构建 backend Java 镜像和 web Nginx 镜像；
- Compose 可以启动完整 Veridex 应用栈，而不再要求宿主机运行应用；
- 提供只管理 Veridex backend/web 的原生 Helm chart；
- 通过 existing Secret 和外部 endpoint 接入 PostgreSQL、RabbitMQ、MinIO、OpenSearch 与 OTel Collector；
- 默认使用 ClusterIP，可选启用标准 Ingress 和外部 TLS Secret；
- 应用 Pod 使用非 root、只读文件系统、最小 capability、受限临时目录和明确资源预算；
- 提供 probes、滚动更新、PDB、可选 HPA、NetworkPolicy 与可选 ServiceMonitor；
- 提供固定镜像清单、校验和、镜像导入脚本、私有 registry 覆盖和离线 Helm 包；
- 通过镜像、Compose、Helm 静态检查与 kind/k3d 集群验收。

## 2. 已确认决策

| 编号 | 决策 | 说明 |
|---|---|---|
| D1 | 双镜像交付 | backend 为 Java 21 运行时镜像；web 为非 root Nginx 静态镜像。两者可独立升级和扩缩容。 |
| D2 | Web 同源反向代理 | web 提供 SPA，并代理 `/api` 与 `/v3/api-docs` 到 backend；不代理 `/actuator`，保持现有 Session/CSRF 同源语义。 |
| D3 | Helm 只管理应用 | 正式 chart 只创建 backend/web 资源。有状态基础设施和观测后端通过 endpoint 与 existing Secret 接入。 |
| D4 | 默认 ClusterIP、可选 Ingress | Ingress 默认关闭；启用时仅暴露 web，TLS Secret 由集群管理员或 cert-manager 管理。 |
| D5 | 管理端口仅集群内部可见 | backend 业务端口为 `8080`，管理端口为 `8081`；独立管理 Service 不进入 Ingress。 |
| D6 | 默认启用 NetworkPolicy | 默认 deny 后按 web/backend 的实际入口和出站依赖放行；无法可靠模板化的企业网络边界必须显式覆盖。 |
| D7 | existing Secret | chart 不生成可用生产 secret，不在 values、ConfigMap、annotations 或 NOTES 中保存明文凭据。 |
| D8 | 离线交付为镜像清单与导入脚本 | 提供镜像清单、SHA-256、save/load 或私有 registry 导入脚本、chart 包和 registry 覆盖 values，不制作包含所有基础设施镜像的单一安装介质。 |
| D9 | 原生 Helm 模板 | 不叠加 Kustomize，也不依赖 generic application chart；资源契约由仓库直接维护和审计。 |

## 3. 非目标

Phase 5-d 不：

- 在 Helm chart 内部署或升级 PostgreSQL、RabbitMQ、MinIO、OpenSearch、Prometheus、Grafana、Tempo 或 OTel Collector；
- 提供数据库、对象存储和搜索数据的备份恢复流程，该能力属于 Phase 5-e；
- 承担企业集群 Ingress Controller、cert-manager、容器 registry、Prometheus Operator 或 CNI 的安装；
- 自动创建生产凭据或把 `.env.example` 的 local 默认值带入 Kubernetes；
- 提供跨区域、多集群、服务网格、蓝绿发布、金丝雀发布或 GitOps 控制器；
- 把应用层 NetworkPolicy 当作企业防火墙、云安全组或 egress gateway 的替代品；
- 修改 5-a API key、5-b telemetry、5-c CSRF/安全边界和业务 API 契约。

## 4. 镜像架构

### 4.1 Backend 镜像

Backend 使用多阶段 Dockerfile：

1. 构建阶段使用固定版本的 Maven/JDK 21 基础镜像，复制 Maven wrapper、父 POM、backend POM 和源码，执行可重复的 `package`；
2. 运行阶段使用固定版本或 digest 的精简 JRE 21 镜像；
3. 只复制 Spring Boot 可执行 JAR，不包含 Maven cache、源码、Node 工具链、测试产物或构建凭据；
4. 以固定非 root UID/GID 运行；
5. 暴露业务端口 `8080` 和管理端口 `8081`；
6. JVM 容器参数使用 `MaxRAMPercentage` 等容器感知选项，不写死主机堆大小；
7. OCI labels 至少包含 source、revision、version、created、licenses；
8. 使用 exec-form entrypoint，并正确转发 SIGTERM，使 Spring Boot 能在 termination grace period 内优雅关闭。

运行镜像根文件系统只读。唯一可写路径是 `/tmp/veridex-parser`，由 Compose tmpfs 或 Kubernetes `emptyDir` 提供，并通过 `VERIDEX_PARSER_TEMP_ROOT` 指向该目录。

### 4.2 Web 镜像

Web 同样使用多阶段 Dockerfile：

1. 构建阶段使用固定 Node 22 镜像和 `npm ci`；
2. 执行 lint/test 由仓库门禁负责，镜像构建只执行 production build，避免重复运行完整测试；
3. 运行阶段使用非 root Nginx 镜像，只复制 `web/dist` 和受控 Nginx 配置；
4. `/` 使用 SPA fallback 到 `index.html`；
5. `/api` 和 `/v3/api-docs` 代理 backend Service，保留 Cookie、CSRF header、request ID 与流式 SSE；
6. `/actuator` 不代理；
7. `index.html` 使用 `no-cache`，带内容 hash 的静态资源使用长期 `immutable` cache；
8. upstream 错误只返回固定 `502/503` 页面，不暴露 backend Service 地址或内部响应细节。

Nginx 以非 root UID/GID 运行，监听非特权端口 `8080`。PID、cache 和临时目录通过 tmpfs/`emptyDir` 提供，根文件系统保持只读。

### 4.3 多架构与供应链元数据

镜像目标平台为 `linux/amd64` 和 `linux/arm64`。本地验证可构建当前平台，发布流程使用 BuildKit/buildx 生成 manifest list。镜像 tag 可读，正式部署推荐使用 digest 锁定；`latest` 不允许作为 chart 默认值。

构建输出应能生成：

- backend/web 镜像 digest；
- OCI metadata；
- SBOM；
- 漏洞扫描结果；
- 镜像清单与 SHA-256 文件。

## 5. Compose 完整应用栈

现有 `deploy/compose/compose.yml` 保留 PostgreSQL、RabbitMQ、Redis、MinIO、OpenSearch 和观测服务，并新增：

- `backend`：从 backend Dockerfile 构建，依赖核心基础设施 health，读取 Compose 环境变量，暴露 `8080/8081`；
- `web`：从 web Dockerfile 构建，依赖 backend health，暴露宿主机 Web 端口；
- backend parser temp 使用 tmpfs 或有 size limit 的临时卷；
- Prometheus 直接抓 `backend:8081/actuator/prometheus`，不再使用 `host.docker.internal`；
- backend OTLP endpoint 指向 `otel-collector:4318`；
- web upstream 指向 `backend:8080`。

Compose 仍以本地开发/验收为目标，允许 `.env.example` 使用明确标记的 local 凭据。生产或试点安装必须使用 Helm existing Secret，不得复用这些值。

## 6. Helm Chart 结构

Chart 位于 `deploy/helm/veridex`，至少包含：

```text
Chart.yaml
values.yaml
values.schema.json
templates/
  _helpers.tpl
  backend-configmap.yaml
  backend-deployment.yaml
  backend-service.yaml
  backend-management-service.yaml
  web-configmap.yaml
  web-deployment.yaml
  web-service.yaml
  ingress.yaml
  networkpolicy.yaml
  poddisruptionbudget.yaml
  hpa.yaml
  servicemonitor.yaml
  serviceaccount.yaml
  NOTES.txt
```

`values.schema.json` 约束必填字段、枚举、端口范围、资源格式和相互排斥配置。模板 helper 统一 labels、selectors、names、service account、image reference、checksum annotations 和 Kubernetes API 兼容性判断。

## 7. Values 与 Secret 契约

### 7.1 镜像

```yaml
global:
  imageRegistry: ""
  imagePullSecrets: []
backend:
  image:
    repository: veridex/backend
    tag: "0.1.0"
    digest: ""
    pullPolicy: IfNotPresent
web:
  image:
    repository: veridex/web
    tag: "0.1.0"
    digest: ""
    pullPolicy: IfNotPresent
```

当 digest 非空时模板使用 `repository@digest`；否则使用非 `latest` tag。`global.imageRegistry` 能把所有应用镜像重写到企业私有 registry。

### 7.2 非敏感配置

backend ConfigMap 只包含非敏感值：

- JDBC URL；
- RabbitMQ host/port；
- MinIO endpoint/bucket；
- OpenSearch URI 与 index prefix；
- embedding provider、dimensions、Ollama base URL/model；
- OTLP endpoint、采样率和 environment；
- management address/port；
- upload/parser budget；
- outbound allowlist；
- trace-body capture policy、TTL 和非密钥参数。

ConfigMap checksum 放在 Pod template annotation 中，配置变化触发滚动更新。

### 7.3 Existing Secret

`backend.existingSecret` 必须引用预先存在的 Secret。固定 key 契约为：

- `db-username`、`db-password`；
- `rabbitmq-username`、`rabbitmq-password`；
- `minio-access-key`、`minio-secret-key`；
- `session-secret`；
- 可选 `trace-fingerprint-key`、`trace-current-key-id`、`trace-current-key`、`trace-historical-keys`；
- 可选模型/provider credential keys。

是否要求可选 key 由启用的 values 决定。例如 trace capture 为 `ERRORS/ALL` 时，chart schema 和预安装校验要求 trace current key 配置；默认 `NONE` 不要求。

chart 不读取 Secret 内容做 checksum，也不把值输出到 NOTES。Secret 名称变化触发 rollout；Secret 内容原地变更后由运维执行 `rollout restart`，避免把 secret 内容 hash 写入 Deployment。

## 8. Kubernetes Workload 契约

### 8.1 Backend Deployment

- 默认副本数适合 20–50 人试点；
- `RollingUpdate`，`maxUnavailable: 0`，`maxSurge: 1`；
- startup/readiness/liveness 均访问管理端口，但使用不同 health group；
- startup 允许 Flyway 和依赖初始化；
- readiness 控制业务流量；
- liveness 只代表进程存活，不因外部 PostgreSQL、RabbitMQ、MinIO、OpenSearch 短暂故障反复重启；
- `preStop` 与 termination grace period 给 SSE 和在途请求留出关闭时间；
- parser `emptyDir` 配置 `sizeLimit`；
- resources requests/limits 必填且有试点默认值。

Flyway 由应用启动执行。本阶段不拆独立 migration Job。滚动策略、readiness 和数据库向后兼容迁移约束共同确保升级安全；未来出现非兼容迁移时必须单独设计迁移流程。

### 8.2 Web Deployment

- web 可独立扩缩容；
- readiness/liveness 使用本地静态 health 路径；
- Nginx upstream 通过 backend Service；
- proxy buffering 对 SSE 路径关闭；
- 保留 `X-Request-Id`，并传递 `X-Forwarded-*`；
- resources requests/limits 有保守默认值。

### 8.3 Service 与 Ingress

- web Service：ClusterIP，供 Ingress 或企业入口访问；
- backend Service：ClusterIP，仅供 web/集群内部 API 访问；
- backend management Service：ClusterIP，仅供 probes、Prometheus/ServiceMonitor 和受控运维访问；
- Ingress 默认关闭；启用时只指向 web Service；
- TLS 只引用 existing Secret，不生成证书；
- hostname、className、annotations 和 path 可配置。

## 9. Pod 安全

backend/web 默认：

- `runAsNonRoot: true`；
- 固定 `runAsUser`/`runAsGroup`；
- `allowPrivilegeEscalation: false`；
- `readOnlyRootFilesystem: true`；
- drop all Linux capabilities；
- seccomp `RuntimeDefault`；
- `automountServiceAccountToken: false`；
- 不使用 hostPath、hostNetwork、hostPID、privileged 或 root init container；
- 临时卷带 size limit；
- Secret 仅通过环境变量或只读 secret volume 注入，不写入镜像层。

默认 ServiceAccount 不授予 RBAC 权限。若未来需要 Kubernetes API，必须通过新的明确设计增加最小权限 Role/RoleBinding。

## 10. NetworkPolicy

启用时创建默认 deny ingress/egress，再建立：

- web ingress：只允许 Ingress Controller namespace/pod selector 或同 namespace 明确来源；
- web egress：只允许 DNS 和 backend 业务 Service；
- backend ingress：业务端口只允许 web；管理端口只允许监控 selector 和同 Pod probes 所需路径；
- backend egress：DNS、PostgreSQL、RabbitMQ、MinIO、OpenSearch、OTLP Collector，以及显式配置的模型 endpoint；
- 不默认允许任意 `0.0.0.0/0`。

Kubernetes NetworkPolicy 无法按 hostname 表达通用外部 FQDN。values 对外部依赖使用以下二选一：

1. namespace/pod selector + port（集群内服务）；
2. CIDR + port（外部服务）。

若企业依赖 IP 动态变化，安装者必须关闭 chart egress policy 并交由 CNI FQDN policy/egress gateway 管理，或提供稳定 CIDR；chart 不静默开放全部出站。

## 11. HPA、PDB 与拓扑

- PDB 默认启用，仅在副本数大于 1 时设置合理 `minAvailable`；
- HPA 默认关闭，可按 CPU/内存启用；
- HPA 与固定 replicaCount 的优先级由 schema 和模板明确；
- topology spread 与 anti-affinity 提供默认 preferred 配置，避免所有副本落在同一节点；
- 单节点试点集群不得因 required anti-affinity 造成 Pod 永久 Pending。

## 12. ServiceMonitor 与可观测性

ServiceMonitor 默认关闭。启用时：

- 仅选择 backend management Service；
- path 固定 `/actuator/prometheus`；
- port 使用明确命名；
- interval/timeout 可配置；
- 不暴露管理端口到 Ingress。

OTLP endpoint 由 values 指向企业 Collector。Collector 不可用时沿用 5-b fail-open 语义，不影响业务 readiness/liveness。

## 13. 受限网络安装

离线交付目录包含：

```text
veridex-offline/
  images.txt
  images.sha256
  charts/veridex-<version>.tgz
  values/registry-values.yaml
  scripts/export-images.sh
  scripts/import-images.sh
  scripts/push-images.sh
  INSTALL.txt
```

`images.txt` 固定列出 backend/web 及 Compose/验收需要的镜像引用与 digest。脚本：

- `export-images.sh` 拉取并 `docker save`；
- `import-images.sh` 校验 SHA-256 后 `docker load`；
- `push-images.sh` 将镜像重标记并推送到指定私有 registry；
- `registry-values.yaml` 覆盖 `global.imageRegistry` 和 image references。

脚本必须 `set -euo pipefail`，拒绝缺失镜像、校验失败和 tag/digest 不一致。Helm 包通过 `helm package` 生成，并记录 chart SHA-256。

## 14. 错误处理与升级行为

- values schema 错误、existing Secret 名称缺失、镜像引用非法或安全上下文配置不合法时，安装前失败；
- Secret 中缺 key 或 backend 安全配置非法时，Pod 启动失败且不 ready，日志只输出固定配置错误，不回显 secret；
- Flyway 失败时 backend 不 ready，新 Pod 不接流量；
- ConfigMap 或 Secret 名称变化触发 rollout；
- backend 暂时不可用时 web 返回固定 502/503，不暴露 upstream；
- readiness 失败只摘流，不触发容器重启；liveness 仅检测本进程；
- Pod 收到 SIGTERM 后停止接收新流量并在 grace period 内完成在途请求；
- NetworkPolicy 配置不足导致依赖不可达时验收失败，不通过扩大默认 egress 绕过；
- chart uninstall 不删除外部数据库、对象、搜索索引、Secret 或 TLS 证书。

## 15. 验证策略

### 15.1 镜像验证

- `docker build` backend/web；
- 检查镜像以非 root 用户运行；
- 以只读根文件系统和受限 tmpfs 启动；
- backend `8080`/`8081`、web health、SPA fallback、API proxy、SSE proxy 正常；
- 镜像不含 Maven cache、源码、Node 工具链、生产 secret 或测试产物；
- 构建 amd64/arm64 manifest；
- 生成 SBOM 与漏洞扫描结果；
- OCI labels 和 digest 完整。

### 15.2 Compose 验收

`docker compose up --build` 后验证：

- backend/web 与所有依赖 healthy；
- web 登录和 `XSRF-TOKEN`/`X-XSRF-TOKEN` 写请求工作；
- 文档上传、异步处理、发布和问答 smoke test 通过；
- Prometheus target `backend:8081` 为 up；
- QA 与 ingestion trace 进入 Collector/Tempo；
- 重启 backend/web 后 PostgreSQL、MinIO、OpenSearch 数据仍可用；
- 业务端口和 web 不暴露 Actuator。

### 15.3 Helm 静态验证

- `helm lint`；
- `values.schema.json` 正反例；
- `helm template` 默认值、Ingress/TLS、HPA、PDB、ServiceMonitor、NetworkPolicy 开关组合；
- Kubernetes schema 校验；
- 检查重复资源、selector 不一致、未命名端口、硬编码 secret、`latest`、privileged/root、缺失 resources/probes/securityContext；
- 模板输出不得包含 known secret sentinel。

### 15.4 集群验收

在 kind 或 k3d 中：

- 安装预置测试依赖 endpoint/Secret；
- 安装 chart 并等待 rollout；
- 验证 startup/readiness/liveness；
- 验证 web/API/CSRF/SSE/上传/问答 smoke test；
- 删除 backend/web Pod 后自动恢复；
- 执行滚动升级并验证无不可用窗口；
- Ingress 开关和 TLS Secret 引用正确；
- 管理 Service 不可从外部入口访问；
- NetworkPolicy 放行必要依赖并拒绝未授权 Pod/egress；
- ServiceMonitor 开关渲染和 selector 正确。

### 15.5 离线验收

在禁止公网拉取的测试路径中：

1. 校验 `images.sha256`；
2. 导入或推送 images；
3. 安装打包 chart 并覆盖私有 registry；
4. 等待 readiness；
5. 执行登录、健康和基本业务 smoke test；
6. 确认安装过程没有公网镜像拉取。

## 16. 验收标准

Phase 5-d 完成需要全部满足：

1. backend/web 镜像可重复构建，并具有 amd64/arm64 发布路径；
2. 两个运行镜像均为非 root、只读根文件系统、最小 capability，且不包含构建工具或 secret；
3. Web 同源代理保持 Session、CSRF、request ID 和 SSE 行为；
4. Compose 可启动完整应用栈，Prometheus 从 `backend:8081` 抓取；
5. Helm chart 只管理 backend/web，并通过 existing Secret 接入外部基础设施；
6. 默认 ClusterIP 与可选 Ingress/TLS 两种模式均通过 lint、template 和集群验收；
7. startup/readiness/liveness、rolling update、termination grace、PDB 和可选 HPA 生效；
8. 管理端口不进入外部 Ingress，ServiceMonitor 只抓 management Service；
9. 默认 NetworkPolicy 执行最小入口/出站权限，未授权流量被拒绝；
10. values schema 拒绝非法镜像、端口、Secret、Ingress、HPA/PDB 和安全配置组合；
11. 镜像清单、校验和、导入/推送脚本、registry values 与 chart 包可支持受限网络安装；
12. `./scripts/verify.sh`、镜像检查、Compose 验收、Helm 校验、集群验收、离线验收和 `git diff --check` 全部通过。

## 17. 固定决策

实施计划不得重新选择：

- backend/web 双镜像；
- web Nginx 同源代理 API，不代理 Actuator；
- 原生 Helm chart，不叠加 Kustomize 或 generic chart；
- chart 只管理应用，有状态依赖与观测后端外部化；
- existing Secret，无生产明文默认值；
- 默认 ClusterIP、可选 Ingress 与外部 TLS Secret；
- backend 管理端口独立且不进入 Ingress；
- 非 root、只读根文件系统、drop capabilities、seccomp、禁用 service-account token；
- 默认 NetworkPolicy，外部目标必须使用 selector 或 CIDR 明确表达；
- ServiceMonitor 默认关闭；
- 离线交付采用镜像清单、校验和、导入/推送脚本和打包 chart；
- 备份恢复与容量报告留给 Phase 5-e。
