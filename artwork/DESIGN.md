# 素笺 · 设计系统

Android Views 实现：视觉只使用 Material 3 Expressive。Apple HIG 用于核对清晰的任务流程、布局适应和无障碍交互，不引入 iOS 的控件、色彩或图标风格。规范参考：[M3E](https://m3.material.io/blog/building-with-m3-expressive)、[M3E 按钮组](https://m3.material.io/components/button-groups/overview)、[Apple HIG · Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)、[WCAG 2.2](https://www.w3.org/TR/WCAG22/)。

| 系统 | 唯一来源 | 使用 |
| --- | --- | --- |
| 色彩 | `res/values*/colors.xml`、`AppTheme`；Android 12+ 动态色覆盖 | primary 只标识选中项；surface/containerLow 分开页面和预览；onSurfaceVariant 用于说明、状态及隐私提示；错误用 onErrorContainer/errorContainer |
| 字体 | `res/values/styles.xml` 的 `Text.Sujian.*` | MDC emphasized 标题/标签，正文和说明用 MDC body；所有文字随系统字体缩放 |
| 形状 | `Shape.Sujian.*`、`Widget.Material3Expressive.*` | 圆角纸页预览、M3E connected 单选按钮组、M3E 进度条和工具栏 |
| 间距与尺寸 | `res/values/dimens.xml` | 4/8/12/16/24/32dp 间距；可操作元素最小 48dp；680dp 最大正文宽度 |
| 动效 | `MaterialStyle.animate` + MDC 组件内部动效 | 小范围内容布局变化用主题 motion tokens；系统关闭动画时不播放，离屏不播放 |
| 图标 | `artwork/icon*.svg` → `scripts/generate-icons.py` | 自适应图标彩色/主题单色同形；市场 PNG 由同一 SVG 渲染 |

交互状态：预览和选项相邻，选择立刻保存；自定义为空是有效的留白预览，不显示伪错误。状态查询和导出结果分别通过无障碍实时区域报告；预览为单一摘要节点，示例文字不重复朗读。按钮组在窄宽/大字号下改为竖排；文本输入可由键盘“完成”结束。降低动态效果时跳过内容过渡。

验证：`python scripts/generate-icons.py --check`、`./build.sh`、`bash tests/build.sh`。`DesignSmoke` 在有设备时覆盖三种模式、深浅色、双倍字号/320dp、48dp 目标、配色对比度、导出中及失败状态。浅色/深色静态方案的正文/容器文字对比度均超过 WCAG 2.2 AA 的 4.5:1；动态配色在真机烟测中另行核查。设备未连接时不宣称通过真机测试。
