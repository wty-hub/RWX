# Vulkan 本机验收工具

先构建当前平台的完整运行包，再执行测试。运行器把运行包复制到新的结果目录，避免构建期间修改正在运行的类文件；游戏使用正常退出流程。

```sh
./gradlew :desktop:platformFatJar
python3 desktop/tools/vulkan_native_matrix.py \
  --jar desktop/build/libs/RWXX-1.0.5-macos-arm64.jar \
  --java /path/to/jdk25/bin/java \
  --operating-state visible-unlocked
```

请按当前平台替换 jar 路径，使用 Java 25 或更新版本。默认结果保存在 `build/rwx-benchmark/<时间>/`。`--output` 可以指定新的目录；已有测试清单的目录不会被覆盖。运行状态由调用者如实填写，工具不会推断窗口可见性或锁屏状态。

默认执行 661／1000／2000 单位的陆空、海空、综合混编九组测试，每组先预热 30 秒，再连续记录三段 30 秒采样。请求 1920×1080，实际引擎视口与 Vulkan 交换链尺寸写入结果，不能仅依据请求尺寸判定。单位使用正常构造、伤害和命令规则，战损会降低存活数；这类窗口不能标为“持续 2000 单位”验收。

随后执行正常模拟下的 2000 单位单一友军闲置基线，以及上传延迟 50 毫秒、呈现延迟 50 毫秒、隐藏窗口 10 秒的独立短测试。闲置容量与持续交战分别报告。完整运行约 24 分钟。可使用 `--units 2000 --mixes all` 缩小矩阵，或用 `--combat-only` 省略诊断测试。

```sh
python3 desktop/tools/analyze_vulkan_matrix.py build/rwx-benchmark/<时间>
python3 -m unittest discover -s desktop/tools -p 'test_*.py'
```

分析器使用帧 CSV 中的单调时钟及画面世代、序号，按引擎窗口的实际起止时间计算帧率、新画面率、重复比例、p95、p99。窗口边界保留前一张画面的身份，防止把重复帧误算为新画面。分位数采用排序后 `int((数量-1)×分位)` 的位置。

呈现指标表示 `vkQueuePresent` 接受提交的时刻，不等于显示器实际扫描输出。原生指标还记录 GPU 时间戳、上传量、等待与提交耗时、常驻上传内存及退休队列。请保留日志中的实际呈现模式、目标帧率、可见／锁屏状态，以及存活单位数后再解释结果。

需要测试未锁定帧率时，先在游戏设置中关闭垂直同步。运行器保留原有显示设置，并记录实际呈现模式；环境中的 `RWX_DESKTOP_TARGET_FPS` 不能绕过 FIFO 模式或平台的原生 drawable 等待。

Kool 原有默认画质使用 4 倍 MSAA，继续保持该默认值。`--msaa-samples 1` 或 `--msaa-samples 2` 可用于单独研究 GPU 成本；运行器显式指定采样数，原生日志记录实际采样数。直接启动游戏时也可用 `RWX_KOOL_MSAA_SAMPLES=1` 或 `-Drwx.kool.msaaSamples=1`，只接受 1／2／4。降低 MSAA 只影响渲染，但仍需要检查选择标记、线条、字体及特殊效果的画面质量；帧率提高不能替代视觉验收。
