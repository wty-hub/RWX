# 桌面端卡顿优化说明

本文记录 RWXX Linux 桌面端从「选中圈卡、操作不跟手」到「约 2000 单位仍能稳住 60fps」的改动。目标是：**只改显示和平台层，仿真与原版铁锈 bit-exact，可以联机。**

约束见 `.cursor/rules/rw-compatibility.mdc`：不改确定性仿真、网络包、存档/回放、地图模组解析结果、校验和、游戏规则。`core/src/main/java/com/corrodinggames/rts/**` 里凡走逻辑路径的行为必须与 `/home/wty/code/TuanHun/铁锈代码` 一致。性能工作放在 `desktop/.../slick`、`slick2d-lwjgl3` 和纯显示代码。

## 1. 问题是怎么定位的

用 JFR 在游戏线程上采样，卡顿分三层，不是单一热点。

| 阶段 | 体感 | 主要证据 |
|---|---|---|
| 选中单位就卡 | 圈和精灵各画一次就 `glBegin` | `drawArc` → `glVertex2f` 约占游戏线程样本 42% |
| 圈和批处理后操作发黏 | 点一下要等十几毫秒才动 | JAWT/AWT 锁等待约 5856 次、平均约 19ms。Kool 覆盖层 `swapInterval=1` 在锁里等到垂直同步（约 17ms） |
| 800～2000 单位开始掉帧 | 画面还能动，但帧间隔顶满 16.6ms | 更新约 42%、绘制约 44%。绘制侧是队伍着色 shader 和阴影/身体换贴图把批次冲成「每次 4 个顶点」 |

对局里 Kool 覆盖层藏起来之后，锁等待从约 12ms 降到约 0.06ms；小地图上回放能到约 300fps。说明锁和呈现才是跟手问题，不是仿真算错。

## 2. 帧预算

60fps 要求 **update + draw ≤ 16.6ms**。桌面循环拆成：等画布锁、游戏工作（update + draw）、swap、帧同步。环境变量 `RWX_PERF_LOG` 按这个拆分打日志（见第 7 节）。

沙盒回放 `[sandbox]Crossing Large (10p)` 约 2450 单位时：

| 版本 | 帧率 | 每帧工作 | 绘制 | 更新 | 锁等待 |
|---|---|---|---|---|---|
| 优化前 | 30–37 | 17–25ms | 13–17ms | 5–10ms | 5–11ms |
| 锁/节奏 + 裁剪/快路径 | 63–67 | ~15ms | 10–11ms | ~4ms | ~0.4ms |
| 多贴图批次 + 队伍着色进批次 | 120–150 | 5–8ms | ~4ms | 2–4ms | ~0.2ms |

最后一版 p50 帧间隔大约 6ms，相对 16.6ms 预算有余量。回放校验和与基线一致（Crossing Large 4199 tick、水吊 2999 tick、小块地 1824 tick）。

## 3. 显示层：少画、合成批

### 3.1 选择圈不再用 50 段 immediate 椭圆

原 Slick `drawOval` 每圈约 50 段 `glVertex2f`。选中一大团单位时，圈比精灵还贵。

- `Graphics.drawEllipseOutlineBatched` 把闭合折线推进 `QuadBatch`
- `SlickGraphicsEngine` 按屏幕半径把段数收到 12～50

只改怎么画，不改哪些单位算被选中。

### 3.2 `QuadBatch`：同状态几何一次提交

文件：`slick2d-lwjgl3/.../QuadBatch.java`。

早期是每个精灵、每段线一次 `glBegin`/`glEnd`。批次收集带颜色的顶点，相同 GL 状态用一次 `glDrawArrays`。改颜色不拆批；改裁剪、FBO、读回、立即模式绘制必须先 `flush()`。`Graphics.setCurrent` / `predraw` / 释放贴图时都会冲批。

贴图切换时只 `submit()`（画出当前顶点、客户端数组保持开着），不要每次都拆掉数组状态。真正回到 Slick 立即模式时才 `flush()` 并 `TextureImpl.unbind()`。

### 3.3 屏幕外裁剪和普通精灵快路径

文件：`desktop/.../slick/SlickGraphicsEngine.kt`。

- 变换后的目标矩形在视口外则直接返回
- 没有自定义 shader、没有特殊混合时走纯贴图四边形，不经过 `withSlickShader`
- `applyClip` / `j()`：裁剪矩形没变就不重设 clip（重设 clip 会冲掉精灵批）

### 3.4 多贴图批次 + 桌面侧队伍着色

2000 单位团的剩余绘制成本来自**每个单位两次冲批**：

1. 阴影一张贴图、身体一张贴图，交替提交 4 个顶点
2. 身体是 `TeamColorTexture`，走 `TeamColorShader`；阴影没有。开关 shader 再冲一次

不能把「先全部阴影、再全部身体」重排，否则后面单位的影子会画到前面单位身体下面，和原版不一致。

做法：`QuadBatch` 一次 draw 最多绑 8 张贴图，顶点里带贴图下标和队伍着色模式。片元逻辑与 `assets/shaders/pureGreenTeamColor.frag`、`hueAddTeamColor.frag`、`hueShiftTeamColor.frag` 对齐。`SlickGraphicsEngine` 对 `TeamColorShader` 不再走 `withSlickShader` 逐精灵上传 uniform，而是把队伍色写进顶点。

同队一团单位（阴影 + 身体 + 炮塔，通常 2～3 张贴图）可以合成很少几次 `glDrawArrays`。绘制从约 11ms 降到约 4ms。

这是显示替换，不改 `TeamColorShader.java` 给仿真或原版客户端用的路径。

## 4. 平台层：别在 AWT 锁里等垂直同步

桌面是双画布：Kool 透明覆盖层 + Slick 游戏画布，共用 X11 JAWT/AWT 锁。谁拿着锁谁就能卡住另一边的输入和绘制。

### 4.1 Kool 覆盖层去掉 vsync、自己控节奏

文件：`PacedSwingWindowSubsystem.kt`、`SwingKoolHost.kt`。

Kool 原来 `swapInterval=1`，在 `KoolGlCanvas.render()` 里等垂直同步，整段持锁约 16～17ms。Slick 的 `syncFrame` 也曾在锁里。结果是操作发黏。

- Kool 和 Slick 都 `swapInterval=0`
- `PacedSwingWindowSubsystem` 在 EDT 上跑 Kool 帧，**等下一帧的 sleep 在锁外**
- Slick `syncFrame` 也移到锁外（`EmbeddedSlickGameContainer`）

锁等待从平均约 19ms 降到接近 0。体感是「操作跟手了」。

### 4.2 对局隐藏覆盖层时不 present

对局里如果没有 HUD 覆盖层，Kool 窗口是隐藏的。XWayland 上对隐藏窗口 `swap` 即使 interval=0 也会阻塞到超时，并且一直占着 AWT 锁。

隐藏时走 `renderWithoutPresenting`：Kool 仍跑一帧（UI 状态、协程、会话），画进后缓冲，**不 swap**。锁占用从约 12ms 降到约 0.06ms。

## 5. UI 文本（显示缓存）

- `TextUtils.wrapLines`：按 `(文本, 字号 bits, 行宽)` LRU 256
- 字体按码点加载，不再用 30 条字符串的 LRU 整串缓存

不改变排版结果，只避免每帧重复测宽和加载字形。

## 6. 仿真：唯一动过的逻辑热点

文件：`UnitSpatialIndex.querySoftCollisionCandidates`，由 `OrderableUnit.updateAllUnitCollisions` 调用。

原 `b()` 把格子里所有单位丢进列表，再在 `resolveSoftCollisionWithUnit` 开头丢掉自己、碰撞组不匹配、互为 target 的单位。格子遍历顺序和插入顺序必须保持，否则软碰撞结果会变。

预过滤是 `b()` 的拷贝，只是在**同一格子、同一插入顺序**下跳过原函数本来就会立刻 return 的单位。不改 `nearbyUnits` 的排序和写入。

约 2400 单位时 update 已经约 4ms。`CustomUnit.update` / `OrderableUnit.update` 没有再改：再动仿真风险大于收益。

## 7. 怎么验证「没改规则」

环境变量（只读游戏状态，默认关闭）：

| 变量 | 作用 |
|---|---|
| `RWX_PERF_LOG=1` 或文件路径 | 每约 5 秒一行：fps、单位数、lock/work/update/draw/swap/sync 的 avg/p50/p95 |
| `RWX_CHECKSUM_LOG=/path/file` | 每 tick 一行：原版 `GameStateChecksum` + 每个可命令单位浮点 bit 的更严 FNV |
| `--replay=` / `RWX_REPLAY_SPEED` | 窗口版自动播回放，方便对校验和 |

不需要窗口时用 `:desktop:headless`。它走同一条 `gameLoop`，不创建 OpenGL。`--checksum=` 和 `RWX_CHECKSUM_LOG` 写的是同一种文件。

```bash
./gradlew :desktop:headless --args='--replay=22.25.33 --ticks=3000 --checksum=build/replay.txt'
./gradlew :desktop:headless --args='--map="maps/skirmish/[p2]Small_Island (2p).tmx" --ticks=600 --checksum=build/map.txt'
```

- `--replay=` 可以用文件名里一段不重复的文字，和窗口版一样。
- `--map=` 走单人地图加载，必须带 `--ticks=N`。回放省略 `--ticks` 时播到结束。
- `--args` 里如果参数含空格，再加一层引号。
- 正常结束退出码 0；加载失败 1；回放读到一半出错 2。
- 已安装的 RWXX 也可以：`--headless --replay=...`。完全退出再启动后才会用到新 jar。

回放必须和优化前 jar 的 checksum 文件逐 tick 比对。沙盒 Crossing Large、水吊、小块地均与基线 IDENTICAL。同一段回放的前 30 tick，无头和窗口版校验和逐行相同。

不要用 16p Mc 劫掠那类回放当 2000 单位基准：它会卡在 tick 1～3（寻路/对话框/等待），测不到团战。

## 8. 中文输入（优化后的回归）

不是性能项，但是同一批桌面窗口改动带出来的。

中文输入法必须挂在**主 `JFrame` 里那个 1×1 隐藏 `JTextField`** 上。Kool 覆盖层是透明 `JWindow`，输入法不会挂上去。覆盖层 `toFront()`、对局里把焦点还给游戏画布，都会打断拼音组字，结果只剩字母。

处理：输入时覆盖层只负责画面（`focusableWindowState=false`），游戏画布在 `PlatformTextInputBridge.isEditing()` 期间不再抢焦点。输入法组字仍不进 Kool 文本（带 `ComposedTextAttribute` 的标记文本会剥掉）。

## 9. 文件对照

| 区域 | 主要文件 |
|---|---|
| 精灵/圈批次、多贴图、队伍色 | `slick2d-lwjgl3/.../QuadBatch.java`、`Graphics.java`、`TextureImpl.java` |
| 裁剪、快路径、队伍色入口 | `desktop/.../slick/SlickGraphicsEngine.kt` |
| Kool 节奏、隐藏不 present | `PacedSwingWindowSubsystem.kt`、`SwingKoolHost.kt` |
| 锁外同步、帧末冲批 | `EmbeddedSlickGameContainer.kt` |
| 帧时间日志 | `SlickFrameTimeLog.kt` |
| 校验和 | `core/.../diagnostics/GameStateTrace.kt` |
| 无头仿真 | `desktop/.../HeadlessMain.kt`、`headless/HeadlessGameSession.kt`、`headless/HeadlessGraphicsEngine.kt` |
| 碰撞预过滤 | `UnitSpatialIndex.java`、`OrderableUnit.updateAllUnitCollisions` |
| 折行缓存 | `core/.../ui/TextUtils.java` |
| IME | `DesktopTextInputController.kt`、`SwingKoolHost.kt`、`SlickCanvasHost.kt` |

## 10. 明确没做的事

- 没有改单位 AI、伤害、寻路结果、命令顺序
- 没有改网络协议或包内容
- 没有把影子改成「先画完所有影子再画身体」（观感会和原版不一致）
- 没有为了快而改 `CustomUnit.update` 控制流
- 没有重打完整发行 zip；只替换桌面 jar

联机仍应与原版铁锈互为主机各测一轮 1000+ 单位房间。仿真路径未改，但最终以实机联机为准。
