# 本地验证记录

最新验证：**2026-10-03（Asia/Shanghai）**。源码摘要与执行记录保存在 `docs/evidence/`。2026-09-30 的 VS Code 重启和 HTTP 样本单独标为历史结果，不作为新增功能的验收依据。

## 环境与完整后端验证

macOS / Apple Silicon arm64，Temurin Java 21.0.12.1，Maven 3.9.16，MySQL 8.4.11，Redis 8.10.2，Spring Boot 3.5.16。数据库与 Redis 复用用户级安装，未使用 H2 或内存 Map 替代主业务存储。

加载本地连接环境变量后执行 `./mvnw -B -ntp clean -Pintegration verify`，最终 **BUILD SUCCESS：68 项单元测试、38 项集成测试，无失败、错误或跳过**。先清理构建目录，避免旧报告与覆盖率混入结果。

| 测试组 | 数量 | 主要内容 |
| --- | ---: | --- |
| AuthServiceTest | 13 | 模拟码、会话、环境门禁与 Redis 异常 |
| ShopServiceTest + ShopCacheTest | 13 | 筛选、商户权限、私有店铺查询与缓存失败路径 |
| VoucherServiceTest + VoucherClaimTransactionTest | 11 | 领取边界、重复领取、事务异常分类 |
| VoucherRedemptionServiceTest | 9 | 核销权限、状态、锁后读取时钟、异常与日志脱敏 |
| TestInfrastructureGuardTest | 22 | Flyway 前阻止误连开发库或错误 Redis 命名空间 |
| AuthIT | 8 | 真实 HTTP、验证码原子消费、会话到期与注销隔离 |
| ShopIT | 9 | 筛选、越权、乐观更新、缓存 TTL 与失效 |
| MerchantShopIT | 6 | 本商户全部状态店铺、归属权限、下线后重新上线 |
| VoucherIT | 5 | 多用户竞争、重复提交、数据库故障回滚、个人数据隔离 |
| VoucherRedemptionIT | 10 | 核销权限、微秒边界、并发、历史权益、回滚与表约束 |

关键实际断言：

- 64 个已登录用户、32 个请求线程争抢 20 张券：20 次成功、44 次 `SOLD_OUT`，库存 0，领取记录 20 条且用户、核销码各不重复。
- 同一用户 32 个并发领取请求：1 次成功、31 次 `ALREADY_CLAIMED`，库存只减 1。
- 同一核销码 32 个并发请求：1 次成功、31 次 `ALREADY_REDEEMED`，只发生一次状态变更，核销不再扣库存。
- 真实 MySQL 触发器分别强制领取插入和核销更新失败：接口 503，事务回滚；移除测试触发器后重试成功。
- 核销期限前 1 微秒可用，等于期限及期限后 1 微秒均拒绝。IT 使用固定时钟和真实 `DATETIME(6)`；锁后读时钟由单元测试调用顺序验证，未执行真实时钟跨界的锁等待实验。
- 商户只能管理自己的店铺；其他商户不能核销，普通用户没有商户权限。店铺下线或活动关闭不撤销仍在期限内的已领权益。
- 同版本两个店铺更新：一个成功、一个 409。个人券查询不能通过额外 userId 越权；公开接口不返回个人核销码。

这些结论针对已执行样本，不能推出生产吞吐、无限并发正确性或用户规模。

证据：[测试清单、源码 SHA-256 与覆盖率](evidence/maven-verify.json)。主代码行覆盖 **413/445 = 92.81%**，分支覆盖 **191/242 = 78.93%**，由本轮单元与集成测试共同产生，不代表不存在遗漏缺陷。源码摘要覆盖 `pom.xml` 与 `src/` 全部文件。

原始报告在 `target/surefire-reports/`、`target/failsafe-reports/`、`target/site/jacoco/`，完整日志在 `work/logs/campuslife/verify-final-20261003.log`；这些原始产物不提交 Git。

## 真实浏览器流程

Playwright 1.62.1，独立 Chrome 154.0.8037.92 无头实例，桌面视口 1440×1080。复用已安装浏览器程序，新建隔离上下文，不读取日常浏览器资料或其他标签页。Python 驱动临时启动 `BrowserTestApplication`，只使用受保护的专用测试库与 Redis 测试命名空间。

**12 个命名场景全部通过：**

1. 预算 18.00 包含均价 18 元的咖啡店，17.99 排除。
2. 页面申请模拟码、登录、领券，并在个人列表看到记录。
3. 重复领取得到 409，退出后清除私有内容，再登录仍能查询原记录。
4. 商户看到自己两家店，包含下线店；上线后出现在公开列表，恢复下线后隐藏。
5. 其他商户打开店铺管理得到 403，不显示编辑表单。
6. 其他商户核销被拒绝，页面不显示成功回执。
7. 本店商户核销成功，再次提交得到 409。
8. 学生刷新后看到“已核销”和核销时间。
9. 注入 503 响应后保留登录状态，恢复请求后可以继续查看记录。
10. 注入 401 后清除会话及已显示的个人券。
11. 延迟已授权的商户列表响应，退出后再放行，旧响应不能重新填入私有内容。
12. OpenAPI 中 8 个受保护操作均声明 Bearer 认证。

除明确标出的响应注入与延迟外，业务操作使用真实页面、HTTP、MySQL 和 Redis。503/401 浏览器注入验证界面状态处理，不等于停掉真实 Redis 的故障演练。未覆盖所有视口、浏览器和辅助功能场景。

证据：[脱敏浏览器报告](evidence/browser-tests.json)；截图：[发现好店](images/discover.png)、[商户工作台](images/merchant.png)。截图已逐张检查，没有测试凭据或个人核销码。原始产物在 `target/browser-tests/`；测试结束已关闭自建浏览器和 Java 进程，共享 MySQL/Redis 服务保留。

## 干净源码与发布准备

从公开文件建立临时副本，不复制 `.env`、`target`、本机工作记录或 `node_modules`，并移除进程中的数据库凭据变量。执行 `./mvnw -B -ntp package`：**68 项单元测试通过，可执行 JAR 打包成功**。副本中的 `pom.xml` 与 `src/` 摘要和完整验证版本一致。此验证复用了用户 Maven 依赖缓存，不等于首次空缓存下载或 Windows 构建验证。

Maven Wrapper 固定 3.9.16，已实际下载、按配置校验并运行。证据：[干净构建记录](evidence/clean-build.json)。临时副本在核对后清理，构建日志保留。

文档使用相对路径，VS Code 使用 `${workspaceFolder}`；`.env`、本机工作记录、构建产物、日志和 npm 依赖被忽略。公开候选文件已检查本机凭据值、个人路径及本地链接，未发现凭据泄露；这不替代未来提交前的检查。

2026-10-03 已推送到 [yewe-li/CampusLife](https://github.com/yewe-li/CampusLife)，当前仓库为私有。首次 [GitHub Actions](https://github.com/yewe-li/CampusLife/actions/runs/37134329039) **完成且全部通过：68 项单元测试、38 项真实服务集成测试、12 个浏览器场景**。远端使用 Ubuntu runner、Java 21、MySQL 8.4 / Redis 8 服务容器和 Playwright Chromium，成功上传测试报告。见 [脱敏远端验证记录](evidence/github-ci.json)。

这次 CI 对应首次代码提交 `dc6482be48ac81ebfaffdd90397adf192070862a`，与上述本地验证的源码摘要一致。后续文档提交只补充发布与验证记录。本地证据中“GitHub CI not executed”描述的是生成该报告时的状态，远端结果单独记录。没有云部署、真实用户或商业效果数据。

## 历史验证：2026-09-30

当时在 VS Code 的 `CampusLife (dev)` 配置启动应用，业务端口 8080、管理端口 8081，健康检查 `UP`；手动重启后，演示领取记录、库存及登录后查询保持一致。见 [历史持久化记录](evidence/persistence.json)。这不是 2026-10-03 新版本的重新启动记录。

当时 `http-smoke.py` 通过 16 项检查，含静态页、OpenAPI、原有 5 个受保护操作、真实登录/领取/查询/退出，以及缓存预热后 200 次公开详情读取。见 [历史 HTTP 报告](evidence/http-smoke.json)。当前脚本的认证元数据检查已扩为 8 个操作，本轮由浏览器测试重新验证这 8 个声明，没有重跑整个旧脚本。

旧读取样本使用 Python urllib、16 线程、本机回环和 VS Code 调试模式，P50 14.22 ms、P95 18.88 ms，仅为单次冒烟样本，不能作为生产 QPS、容量上限或优化提升比例。

## 复现与限制

先按 [运行指南](setup.md) 配置服务和本地连接信息：

```sh
sh scripts/dev.sh test
python3 -B scripts/verification-report.py
# 可选浏览器依赖仅需安装一次；详见运行指南
npm ci
# 已安装 Google Chrome 时：
PLAYWRIGHT_CHANNEL=chrome npm run test:e2e
```

集成和浏览器测试都会重置专用 `campuslife_test` 与 Redis DB 1 的 `campuslife:test:` 前缀，不要并行执行或在测试位置保存有价值的数据。保护逻辑在 Flyway 前检查连接目标。

本轮没有停止共享 Redis 进行服务级故障演练，也没有做生产负载、真实短信、支付或云部署验证。Flyway 11.7.2 对 MySQL 8.4 有超出其已测试范围的提示；本机迁移与测试通过不等于官方认证。已有 V900 开发库不可直接切换 prod profile 复用。
