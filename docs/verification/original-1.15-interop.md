# 原版 1.15 双向联机验收工具

`desktop/src/test/tools/original_compatibility.py` 默认只准备文件。`--run` 才启动 RWX 和原版客户端，依次进行 RWX 主机、原版主机两个方向，默认各 600 秒。性能测试期间不要并行运行此工具。

## 原版来源与隔离

本机 Steam 安装中的 `game-lib.jar` 字节码打印版本 `1.15`、游戏协议代码 `176`，主类为 `com.corrodinggames.rts.java.Main`。已核对的 SHA-256 为：

```text
8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9
```

工具校验并复制这个基础包；不会把 `RWPP-macos.jar`、`generated_lib` 或安装根目录的覆盖类放进 classpath。基础包的 Manifest 包含 `Class-Path: .`，所以复制到干净目录也隔离了安装目录中的覆盖类。依赖及 x86_64 本地库仍取自安装的 `libs` 和安装目录。原版的 assets/res/font 是只读使用的目录链接，保存、回放、模组目录和工作目录单独创建。RWX 的 `launch.dir` 与两边的 `user.home` 也单独设置。

原版支持 `-nodisplay -noresources -nosound -nomusic -nomods -nobackground -debug PORT:token`。`-nodisplay` 仍创建小型 OpenGL/LibRocket 上下文，并非没有 GPU 的服务器。工具不使用不存在的 `-headless` 或 `-server` 参数，也不改写已安装文件或首选项。

本机原版 JVM、Rocket Connector/Core 仅有 x86_64 架构。实际启动原版 JVM和系统通用二进制的 x86_64 切片都返回 `Bad CPU type in executable`，没有可用的 Rosetta 翻译环境。因此原版原生 UI 客户端启动受阻，不能把文件准备当作原生客户端通过。

`--original-peer-mode headless-core` 选择独立的 `OriginalHeadlessPeer.java` 测试适配器。该模式只替换上下文、空绘图及启动/控制入口，直接使用基础包中的原版引擎、网络和命令实现；基础包优先于适配器 classpath，未重写原版类。结果明确记录 `headless-original-core` 和 `nativeOriginalUi=false`，另外记录适配器源码哈希。它验证真实原版核心的协议与模拟兼容性，不能宣称原版原生 UI 客户端验收已完成。

## 地图与操作

共享地图保持 Small Island 的地形不变，通过原版支持的地图 `objectgroup` 中 `unit` / `team` 属性放置 136 个单位，覆盖 13 种陆、海、空及基地类型。地图由主机正常载入和传给客户端，不在联机过程中直接创建单位。此场景验证混编联机，不能替代全部 177 种形态的 GPU 视觉验收。

RWX 只在设置 `RWX_COMPAT_PROBE_PORT` 后提供绑定 `127.0.0.1` 的测试 API。每个操作通过 `GameSession.submitSessionTask` 在引擎所有者线程执行；HTTP 响应等待真实完成结果。主机、加入、开始、载入采纳和移动使用现有会话或原版命令 API。原版使用其自带 debug 脚本接口；没有修改模拟步长、同步校验周期、生命值或命令规则。

原版 `function` 响应以 NUL 结束，`script` 响应为 `done`。加入房间采用 `script joinServer(...)`，避免 `function` 的调试执行标志阻止原版加入操作。原版 `customMap` 枚举序号为 1。主机和客户端记录同步通过数的方式不同，因此每个方向采集客户端的实际通过计数，同时采集 RWX 的各连接同步计数。

## 运行与证据

先准备并运行不启动游戏的协议测试：

```sh
python3 -B -m unittest discover -s desktop/src/test/tools -p 'test_*.py'
python3 -B desktop/src/test/tools/original_compatibility.py --prepare-only
```

构建后复制运行包到独立路径，固定哈希，再启动两边。例：

```sh
python3 -B desktop/src/test/tools/original_compatibility.py --run --hide-window \
  --rwx-command 'java -Drwx.desktop.renderer=kool -Drwx.kool.backend=vulkan -jar /absolute/path/rwx-interop-frozen.jar'
```

RWX 当前编译目标为 Java 25，应使用明确的 Java 25 或更高版本路径，避免默认 `java` 指向 Java 21。当前窗口由 Swing/AWT 承载，运行命令不要添加 `-XstartOnFirstThread`：本机该参数使 AWT 事件线程卡在 macOS 原生焦点请求，窗口操作无法完成。原版核心适配器例：

```sh
python3 -B desktop/src/test/tools/original_compatibility.py --run --hide-window \
  --original-peer-mode headless-core --original-java /opt/homebrew/opt/openjdk@25/bin/java \
  --rwx-command '/opt/homebrew/opt/openjdk@25/bin/java -Drwx.desktop.renderer=kool -Drwx.kool.backend=vulkan -jar /absolute/path/rwx-interop-frozen.jar'
```

每个方向输出 `manifest.json`、`samples.ndjson`、两边日志和 `result.json`。每秒记录 tick、活动连接、同步通过/错误、重同步、命令队列和 RWX 实际执行命令字节；每 20 秒两边各发送一个普通移动命令。验收要求整个窗口保持两个人类玩家和连接、没有不同步或重同步、tick 不连续停滞 30 秒、窗口内同步通过数和命令执行数增加、实际持续至少 600 秒。短于 600 秒的启动烟测结果明确为 `passed=false`。

`--hide-window` 在 RWX 启动 60 秒后隐藏窗口 10 秒。工具使用同一个 JVM 的引擎时钟与真实窗口操作日志对齐，要求隐藏期间 tick 持续增长且没有不同步或重同步。同步通过使用隐藏前的最近样本与恢复后首次新增通过的样本作边界对照；该完成时刻必须落在实际观察到的原版校验周期加 3 秒网络余量内。周期由实际 tick 推进率和原版公布的校验 tick 间隔计算，保留协商步长，不假定 60 tick/s。结果分别记录严格隐藏区间内的通过数变化、恢复后的等待时间、真实窗口与画布可见性和首末样本。只有计时器配置、没有真实隐藏和推进证据时验收失败。

文件准备、工具测试和可运行包构建均不代表原版联机验收通过。只有两次真实 600 秒对局的结果才满足本项时长要求。

## 2026-10-01 实际原版核心结果

两个真实方向均通过。运行使用固定 v4 包，SHA-256 为 `41ea70f7234f14dc2b502601da474731909ecd34758d24b4dc32c0b125839d50`，显式保持默认 MSAA 4。原版基础包和适配器源码哈希、完整边界证据见 [验收摘要](original-core-interop-2026-10-01.json)。

| 主机 | 实际时长 | tick | 客户端校验通过 | 实际执行移动命令 | 不同步 / 重同步 |
| --- | ---: | --- | ---: | ---: | --- |
| RWX | 600.403 秒 | 149 → 36167 | 120 | 58 | 0 / 0 |
| 原版核心 | 600.723 秒 | 178 → 18199 | 60 | 58 | 0 / 0 |

两边均保持两名玩家、一个连接和 136 个单位。原版主机协商步长为 2，RWX 主机为 1；原版规则保持各自协商结果。实际隐藏画布和窗口分别持续 10.010 和 10.006 秒，严格隐藏采样区间分别推进 488 和 243 tick，各新增一次校验通过，并各执行两条正常网络命令。

完整逐秒状态及命令字节保留为压缩证据：[RWX 主机](original-core-rwx-host-2026-10-01.ndjson.gz)、[原版主机](original-core-original-host-2026-10-01.ndjson.gz)，合计约 89 KB。原始两边日志、运行目录、地图和结果仍位于 `desktop/build/original-interop/core-acceptance-v4`。

此结果覆盖未改动的原版 1.15 核心及真实网络协议，原版原生 UI 没有启动。它不证明全部 177 种形态的 GPU 视觉、全部动作或持续交战性能。稍后加入的“成功呈现后确认相机”修正也没有包含在此固定 v4 包内，应由单独的渲染与输入验证报告说明。
