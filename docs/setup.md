# CampusLife 运行指南

本指南适用于克隆或下载本仓库后的开发环境。Java、MySQL、Redis、Maven 可以使用已有的全局或用户级安装；不要因为换了源码目录就重复安装或重建已有数据库。

## 1. 环境与第一次验证

- JDK **21**，`java -version` 与 VS Code 的 Java 运行时应指向同一主版本。
- MySQL **8.4**，服务可连接；使用独立的开发库和测试库。
- Redis **独立实例**，可使用 DB 0 和 DB 1；集成测试不支持只有 DB 0 的 Redis Cluster。
- VS Code 与 Extension Pack for Java（可选，推荐用于断点学习）。
- Maven 可选。`mvnw` / `mvnw.cmd` 固定 Maven **3.9.16**，首次从 Maven Central 获取发行包，缓存于用户 Maven 目录；项目依赖也使用正常的 Maven 缓存，不放入 Git。

进入含 `pom.xml` 的根目录：

```sh
java -version
./mvnw -version
./mvnw test
```

Windows PowerShell 对应为 `java -version`、`.\mvnw.cmd -version`、`.\mvnw.cmd test`。单元测试不需要 `.env`、MySQL 或 Redis；首次构建需要网络下载依赖。已有 Maven 可将 `./mvnw` 换成 `mvn`。

## 2. 准备 MySQL 与 Redis

### MySQL：使用专用数据库账号

复用已正常运行的服务。在 MySQL 管理员会话中按需执行以下 SQL；密码是占位示例，需要自己替换。若同名账号已存在，先检查其用途与权限，不要重置已有账号密码。

```sql
CREATE DATABASE IF NOT EXISTS campuslife
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS campuslife_test
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'campuslife'@'localhost'
  IDENTIFIED BY 'REPLACE_WITH_YOUR_LOCAL_PASSWORD';
CREATE USER 'campuslife'@'127.0.0.1'
  IDENTIFIED BY 'REPLACE_WITH_YOUR_LOCAL_PASSWORD';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON campuslife.* TO 'campuslife'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON campuslife.* TO 'campuslife'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, TRIGGER
  ON campuslife_test.* TO 'campuslife'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, TRIGGER
  ON campuslife_test.* TO 'campuslife'@'127.0.0.1';
```

这些权限覆盖应用 CRUD、Flyway 创建/调整表结构，以及测试库故障注入的触发器；没有授予其他库权限或全局管理员权限。若通过容器网络连接，账号 host 应由数据库管理员按实际来源限定，上面的回环地址示例不适用于任意远程来源。

`VoucherIT` 会在**测试库**创建并删除临时触发器，故意使领取记录插入失败，以验证库存回滚。如果 MySQL 开启二进制日志且限制创建触发器，即使有 TRIGGER 权限也可能失败；可使用关闭 binlog 的独立本机测试实例，或由管理员在专用测试实例配置允许受信任的触发器创建。不要为通过测试给业务账号授予全局 SUPER，也不要未经评估更改共享/生产实例。

不要手动复制建表 SQL 或反复执行种子 SQL：应用首次启动由 Flyway 自动执行。已有数据库会保留历史记录，并通过新迁移升级。开发种子是 V900；核销字段的新增迁移为 V901，不修改已经执行的 V1。

### Redis：复用已有实例

应用默认连接 `127.0.0.1:6379`，开发使用 DB 0；集成测试固定使用 DB 1 和 `campuslife:test:` 前缀。配置相应密码。若你的本机实例明确没有密码，`.env` 中的 `REDIS_PASSWORD` 留空即可，不要把示例占位密码当成真实配置。

无需运行 FLUSHALL、FLUSHDB 或清空共享缓存。源码与测试不会要求删除其他项目的数据。

## 3. 配置环境变量

在项目根目录复制 [.env.example](../.env.example) 为 `.env`，**已有 `.env` 时保留原文件**：

```sh
cp -n .env.example .env
```

Windows PowerShell：

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
```

填写 `DB_USER`、`DB_PASSWORD`、`REDIS_PASSWORD`；有自定义端口或地址时填写对应变量。使用 `scripts/dev.sh` 时 `.env` 按 POSIX shell 的 `KEY=value` 语法加载，特殊字符需要正确引用；只使用自己维护的本地配置文件。

| 变量 | 默认 / 用途 |
| --- | --- |
| DB_USER / DB_PASSWORD | `campuslife` / 空；开发与测试使用同一个账号 |
| DB_URL | 本机 3306 的 `campuslife`，JDBC 参数约定 UTC |
| TEST_DB_URL | 本机 3306 的 `campuslife_test`；只供集成测试 |
| REDIS_HOST / REDIS_PORT / REDIS_PASSWORD | `127.0.0.1` / `6379` / 空 |
| SERVER_PORT / MANAGEMENT_PORT | `8080` / `8081` |

完整 JDBC URL 示例保存在 `.env.example`。只改 URL 中需要的主机、端口、库名，保留 UTC 参数。示例 JDBC URL 禁用 TLS，仅用于本机开发；远程部署应另行配置数据库传输安全。

直接运行 Maven/Wrapper **不会自动读取 `.env`**。下面的 VS Code 调试配置和 `scripts/dev.sh` 会加载它；其他运行方式需显式提供进程环境变量。

## 4. 在 VS Code 运行与调试

1. “打开文件夹”选择整个仓库根目录，等待 Java 项目导入完成。
2. 确认 MySQL、Redis 已启动，根目录 `.env` 已填写。
3. “运行和调试”选择 **CampusLife (dev)**，按 **F5**。
4. 浏览 <http://127.0.0.1:8080/>。可在 Controller 或 Service 设置断点，再从页面发起请求。
5. **Shift+F5** 停止 Java 应用；MySQL/Redis 和持久化数据不受影响。

[launch.json](../.vscode/launch.json) 使用 `${workspaceFolder}`，无需修改为某个人的绝对路径。若 Java 文件提示无法解析依赖，先看 Java 导入/构建的第一处报错，而不是重新安装所有工具。

## 5. 命令行运行、测试与打包

macOS / Linux，在项目根目录执行：

```sh
sh scripts/dev.sh run       # 读取 .env 或已有环境变量，以 dev 启动
sh scripts/dev.sh unit      # 单元测试，不要求 .env
sh scripts/dev.sh test      # 集成测试，需要真实 MySQL / Redis
sh scripts/dev.sh package   # 编译、单元测试、JAR，不要求 .env
```

脚本优先使用 PATH 中的 `mvn`，没有时使用项目 Wrapper。直接调用 `./mvnw test` 或 `./mvnw package` 不加载 `.env`。`run` / `test` 在缺少 `.env` 且没有设置 DB_PASSWORD 环境变量时提前提示配置；数据库实际连接会进一步验证账号和密码。已有进程环境变量也是有效配置方式。

Windows 首选 VS Code F5。需要 PowerShell 运行时，在当前进程设置变量，再使用 Wrapper；不要用 `Invoke-Expression` 执行 `.env`：

```powershell
$env:DB_USER = 'campuslife'
# Read-Host 避免将密码字面量写进命令历史；本例要求 PowerShell 7。
$env:DB_PASSWORD = Read-Host 'Local database password' -MaskInput
$env:REDIS_PASSWORD = Read-Host 'Local Redis password (empty if none)' -MaskInput
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

Windows PowerShell 5.1 没有 `-MaskInput`，可通过 VS Code `.env` 运行，或使用系统提供的安全方式设置当前进程环境变量。配置后 `.\mvnw.cmd -Pintegration verify` 执行集成测试。macOS/Linux 对应为 `./mvnw -Pintegration verify`，同样需先提供环境变量。

**集成测试会重置 `campuslife_test` 的业务数据。** 保护逻辑在 Flyway 初始化前要求测试库名精确匹配、Redis DB 为 1、前缀为 `campuslife:test:`，拒绝其他连接替代项。开发库不作为测试目标；不要把有价值的数据放进专用测试库。

| 产物 | 位置 |
| --- | --- |
| 单元测试结果 | `target/surefire-reports/` |
| 集成测试结果 | `target/failsafe-reports/` |
| verify 后的覆盖率 | `target/site/jacoco/index.html` |
| 可执行 JAR | `target/campuslife-1.0.0.jar` |

打包后，在已设置连接环境变量的终端使用 `java -jar target/campuslife-1.0.0.jar --spring.profiles.active=dev`。运行前停止占用 8080/8081 的其他开发实例，不要同时从 VS Code 和命令行重复启动。

## 6. 可选：真实浏览器流程检查

运行应用不需要 Node.js。只有执行浏览器测试时才需要 **Node.js 20+、npm、Python 3**，以及前述 Java 21、MySQL 和 Redis。当前测试驱动支持 macOS、Linux 和 WSL；Windows 原生的 Java 构建与 VS Code 调试仍按前文使用。

在项目根目录执行：

```sh
npm ci
npx playwright install chromium
npm run test:e2e
```

`npm ci` 按锁文件安装 Playwright **1.62.1** 到项目 `node_modules/`。Chromium 下载到 Playwright 的用户浏览器缓存，不需要提交到 Git；Linux 若提示缺少浏览器系统库，应由环境管理员按 Playwright 提示配置所需依赖。

已经安装 Google Chrome 时，可以复用它，不下载 Chromium；仍需要先 `npm ci`：

```sh
PLAYWRIGHT_CHANNEL=chrome npm run test:e2e
```

测试启动单独的浏览器实例与隔离会话，不依赖你日常浏览器的标签页或登录状态。驱动从 `.env` 或已有进程环境变量读取连接信息；`.env` 只接受普通 `KEY=value`，不执行其中的 shell 命令，已有环境变量优先。

`npm run test:e2e` 调用 `scripts/browser-tests.py`。它临时启动一个测试 Java 应用，随机选取本机业务端口；在 Flyway 运行前校验目标为 `campuslife_test`、Redis DB 1 与 `campuslife:test:` 前缀，随后重置测试数据，并运行真实浏览器操作。测试应用不使用开发库，也不依赖 8080 上已有的开发应用。

**不要与 `sh scripts/dev.sh test` 或另一份浏览器测试同时运行。** 它们共享同一专用测试库，互相重置会使结果失真。测试结束会关闭自建 Java 进程和浏览器，保留数据库服务以及 `target/browser-tests/` 中的诊断产物；不会关闭你的开发应用或共享 MySQL/Redis。

脚本存在不代表已经执行成功，覆盖场景和实际结果以 [验证记录](verification.md) 为准。测试是用于核对界面、HTTP 和持久化是否配合工作，不替代人工体验，也不代表多浏览器或生产环境都已验证。

## 7. 已有 Mac 开发环境的兼容入口

`scripts/local-services.py` 是为已有用户级目录布局提供的辅助脚本，**不是通用安装器，也不是新电脑的必经步骤**。仅当机器已采用以下布局且配置确属当前环境时使用：

| 内容 | 原有布局 |
| --- | --- |
| 工具 / 命令入口 | `~/.local/opt/` / `~/.local/bin/` |
| MySQL 数据 | `~/Library/Application Support/MySQL/8.4-data/` |
| Redis 数据 | `~/Library/Application Support/Redis/data/` |
| 服务配置与凭据 | `~/.config/campuslife-dev/` |
| 服务日志 | `~/Library/Logs/CampusLife/` |

```sh
python3 scripts/local-services.py status
python3 scripts/local-services.py start
# 确认没有其他项目依赖这组实例后，才在不再使用时执行 stop。
python3 scripts/local-services.py stop
```

脚本沿用这套服务配置与数据位置，首次初始化生成随机凭据及 `.env`，不覆盖已有 `.env`，不安装开机自启。`stop` 关闭的是这组共享的 MySQL/Redis 实例，会影响同时使用它们的其他项目；不会删除数据库数据。

其他安装方式使用对应服务自己的启动命令即可，不要为适应本脚本迁移现有登录状态、数据库或工具。

`scripts/persistence-check.py` 同属这套旧 Mac 布局的可选历史验收脚本，依赖 `~/.config/campuslife-dev/mysql-admin.cnf` 和已存在的演示领取记录。它只比较手动重启前后的记录与库存，并核对认证后的历史查询；不会自动启动、停止或重启应用，不是通用环境的首次运行步骤。

## 8. 常见问题与边界

- **端口被占用**：先检查是否已在 VS Code 启动；可改 SERVER_PORT / MANAGEMENT_PORT。连接变量不会自动改写数据库服务的监听端口。
- **数据库连接拒绝/认证失败**：检查服务是否启动、账号 host、密码及 URL；不要把包含凭据的 `.env` 或日志贴到公开 issue。
- **验证码功能不可用**：模拟码只在显式启用的 dev/test 模式开放；prod 没有真实短信供应商。
- **重复领取/核销返回 409**：先查询已有记录；重启应用不是重置演示数据。
- **Flyway 提示 MySQL 超出已测试范围**：当前依赖版本对 MySQL 8.4 有该提示；本地迁移测试结果以 [验证记录](verification.md) 为准，不等于上游官方兼容认证。
- **切换 prod**：开发库已加载 V900 种子迁移，不能直接复用并只切 profile；应使用独立数据库和对应初始化方案。
- **GitHub CI**：workflow 配置不等于远程已通过；以仓库 Actions 中实际执行记录为准。

`.env`、构建目录、运行日志和本机操作资料由 `.gitignore` 排除；源码、`.vscode`、Wrapper、文档与脱敏验证证据保留在仓库中。忽略文件不会删除本地内容，也不会自动移除已经进入 Git 历史的文件。
