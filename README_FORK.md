# Eta（fork：默认 DeepSeek Harness）

本仓库是 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 的 fork，唯一改动的目的是：
**把 Linux 环境里的 Coding Agent 从写死一个 Kimi Code，改成可插拔的 harness，默认使用 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）。**

上游 Eta 本体、Agent、模型供应商、界面等全部保持原样。

## 为什么需要这个 fork

上游 Linux 环境只提供 Kimi Code 一个 coding harness，且没有配置自定义模型的入口。`@deepseek-ai/dsh` 是 DeepSeek 官方的 coding agent，自带模型设置页（可添加任意 OpenAI 兼容服务），更适合这个用途。上游目前没有相关 issue，所以这里先以 fork 形式提供可用构建。

## 改了什么

**1. harness 变成一张表，而不是写死的分支**

新增 `ui/app/LinuxAgentHarness.kt`：每个 harness 只声明四件事——安装 profile、守护任务命令、启动日志里的 Web UI 地址格式、预注入凭据的 provider 引用。安装、就绪判定、启动、复用已有实例、停止的流程**完全共用**，新增一个 harness 不需要改流程代码。

| harness | 命令 | 说明 |
|---|---|---|
| `DeepSeek Harness`（默认） | `NARB_DISABLE_NATIVE_CACHE=1 DSH_HOME=/root/.dsh dsh web --no-open --host 127.0.0.1 --port 0` | 官方 dsh，npm 分发 |
| `Kimi Code`（保留，可选） | `kimi web --no-open` | 行为与上游一致 |

**2. 修复一处必然失败的地址解析**

DeepSeek Harness 启动后打印的是 `http://127.0.0.1:<port>/?token=<token>`，而 Kimi Code 用 `/#token=`。上游正则只认 `/#token=`，直接换命令会一直等不到地址（`URL_TIMEOUT`）。现在两种分隔符都接受。

**3. 安装带原生扩展编译的降级链**

dsh 依赖 `node-pty` / `@deepseek-ai/node-addon-system` 等原生扩展，Linux arm64 没有预编译产物，必须现场编译。因此：

- Debian 补 `build-essential`、Alpine 补 `build-base`，两者都补 `python3`；
- 安装按 **镜像源 → 官方源 → `--ignore-scripts`** 逐级降级，最后一次即使原生扩展没编出来也能得到可启动的 dsh；
- 顺带把移动端网页插件 `dsh-web-mobile` 装进 dsh 的 `web` profile（官方 Web UI 是桌面布局，竖屏必须靠它），失败只告警不阻断。

**4. 凭据预注入，但可被覆盖**

如果 Eta 里已经配好 DeepSeek API Key，首次启动前写入 `$DSH_HOME/.credentials.yaml`（仅在文档不存在时写一次）。之后在 dsh 设置页保存的密钥会覆盖它，Eta 不会把用户轮换过的密钥改回去。

> 刻意**不用环境变量**注入：dsh 的凭据解析把进程环境当作优先级最高的只读层，用环境变量预注入会让它的设置页再也改不动密钥。

**5. fork 自建发布**

- `.github/workflows/fork-release.yml`：打 `v*` 标签即可出 Debug APK 并自动创建 Release，**不需要签名密钥**；上游 `android-release.yml` 需要 `ETA_RELEASE_*` 四个 Secrets 且标签必须严格等于 `v<versionName>`，在 fork 里无法满足。

  **首次使用前请在 fork 仓库 Settings → Actions → General 里把 Workflow permissions 设为 `Read and write`**，否则创建 Release 的一步会被拒绝（Artifact 仍会正常上传）。
- `-Peta.updateRepo=owner/name`：App 内的更新检查默认指向上游仓库，fork 构建指向本仓库，否则安装包会提示用户去装上游版本（fork 的改动会静默丢失）。默认值不变，上游构建行为完全一致。

## 构建

```bash
# 本地（需 JDK 25 与 Android SDK 37）
./gradlew --no-configuration-cache "-Peta.updateRepo=luckxine/Eta" :app:assembleDebug

# 发布：打标签即触发 CI 出包
git tag v3.3.1 && git push fork v3.3.1
```

## 安装后怎么用

1. Eta → Linux 环境页 → 安装（Debian 或 Alpine 均可）→ 安装 **Node.js** → 安装 **DeepSeek Harness**（首次会现场编译原生扩展，需要几分钟）。
2. 同一张卡片上的按钮变成「启动 Web UI」，点它即以守护任务常驻 dsh 并拉起浏览器；也可以在终端直接运行 `dsh`。
3. 首页溢出菜单的「启动 Web UI」指向**最后一次启动过**的那个 harness（跨启动保留）。
4. Kimi Code 仍可单独安装并启动，两个 harness 互不影响、各自复用各自的实例。

## 已知限制

- 默认的 dsh 安装是「镜像源优先」。国内网络下可用；若镜像缺包会回落到官方源。
- `dsh-web-mobile` 是第三方网页插件，插件市场不可达时会在安装输出里留下 `ETA_DSH_MOBILE_PLUGIN_SKIPPED`，此时 Web UI 仍是桌面布局。
- 免 Root（PRoot）与 Root（chroot）两种后端都支持；harness 复用判定按「发行版 + 后端 + 身份」分组。
- 本 fork 不改变上游的 PolyForm Noncommercial License 1.0.0，同样仅限非商业用途。

## 与上游同步

```bash
git fetch origin && git merge origin/main   # 冲突面基本只有 ui/app 下的 harness 文件与四处文案
```

改动按上游的代码习惯组织（harness 差异集中在 `LinuxAgentHarness`，文案走三套 locale 的字符串资源，新增 `InstallTarget` 用穷尽 `when` 保证漏改会编译失败），因此同步冲突可控。
