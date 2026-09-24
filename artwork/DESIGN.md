# 素笺 · 设计系统

Android Views 实现：视觉与组件只用 Material 3 Expressive（MDC `material:1.14.0`）。Apple HIG 只用来核对任务流程、选项表述、布局适应与无障碍交互，不引入 iOS 的控件、色彩或图标风格。冲突时以平台惯例、可用性与无障碍为先。规范参考：[M3E](https://m3.material.io/)、[M3 Lists](https://m3.material.io/components/lists/overview)、[Apple HIG](https://developer.apple.com/design/human-interface-guidelines/)、[WCAG 2.2](https://www.w3.org/TR/WCAG22/)。

## 页面结构

M3E 分段列表（Android 16 设置页的写法）：`surfaceContainer` 页面上是成组的列表项，组内以 2dp 缝隙分隔，每组上方一行 primary 色的小标题。

| 组 | 组件 | 说明 |
| --- | --- | --- |
| 状态 | `ListItemLayout` + `ListItemCardView`，前导 `LoadingIndicator` / `MaterialShapes` 徽记 | 生效时是普通列表项；旧版未重启用 tertiaryContainer，未生效或缺便签用 errorContainer。点按重查，尾部刷新图标提示可点 |
| 分享长图底部 | 预览项 → 三个单选项 → 自定义时的输入项 | 选中项取列表的 selected 令牌（secondaryContainer），形状变为整圆角；自定义输入出现时，组内首/中/尾形状随之重排 |
| 导出便签 | 单个列表项，内含全页唯一的 filled 按钮 | 进行中显示 wavy 线性进度；失败时按钮变为「重试导出」，说明放在 errorContainer 中 |

## 令牌

| 系统 | 唯一来源 | 使用 |
| --- | --- | --- |
| 色彩 | `res/values*/colors.xml`、`AppTheme`；Android 12+ 动态色覆盖 | 页面 surfaceContainer，列表项 surfaceBright（深浅色下都比页面亮一级），预览纸页 surfaceContainerHigh；primary 只用于组标题与导出按钮 |
| 字体 | MDC 列表项文字样式 + `Text.Sujian.*` | 列表项标签 bodyLarge、辅助文字 bodyMedium、概述 labelSmall；组标题 titleSmall Emphasized；全部随系统字号缩放 |
| 形状 | MDC 列表项形状状态表；`Shape.Sujian.*` 取自 M3 圆角刻度 | 列表首/中/尾/单项与选中形状由 MDC 给出；预览纸页 Large，错误说明 Medium；状态徽记 Cookie 9（正常）/ Sunny（异常） |
| 间距与尺寸 | `res/values/dimens.xml` | 页边 16dp、组内缝隙 2dp、8/12/16/24/32dp 间距；可操作元素不小于 48dp；正文最大宽度 680dp |
| 动效 | `MaterialStyle.animate` | 布局变化用主题 `motionSpringDefaultSpatial`，淡入淡出用 `motionSpringDefaultEffects`，按弹簧衰减到 0.1% 的时长播放；系统关闭动画时不播放，离屏不播放。组件内部动效（选中形状变化、按钮按压、加载指示）由 MDC 自带 |
| 图标 | `artwork/icon*.svg` → `scripts/generate-icons.py` | 自适应彩色/主题单色同形；市场 PNG 由同一 SVG 渲染 |

## 交互与无障碍

- 选项即时保存，无保存按钮与离开确认。每个选项都写明结果，不必先选再读说明。
- 自定义留空是合法状态（与留白相同），以辅助文字说明，不标错误。输入框可用键盘「完成」收起。
- 三个选项向读屏报告为单选按钮，带「第几项、共几项」与选中状态；预览是一个摘要节点，示例文字不逐行朗读；组标题标为标题。
- 模块状态与导出结果通过实时区域播报。状态除颜色外还以徽记形状、图标与文字区分。
- 320dp 宽、两倍字号下所有文字换行而不截断。

## 验证

`python scripts/generate-icons.py --check`、`./build.sh`、`bash tests/build.sh`，再在设备上运行 `DesignSmoke`：覆盖三种模式的保存与冷启动回读、分段位置状态、单选语义、深浅色、失败与进行中的导出、两倍字号加 320dp、48dp 目标，以及正文与各容器文字对比度不低于 WCAG 2.2 AA 的 4.5:1（按设备当前配色，动态色开启时即动态色）。静态配色的对比度另行按 `colors.xml` 计算，最低 5.5:1。
