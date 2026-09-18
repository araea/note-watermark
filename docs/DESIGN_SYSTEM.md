# 素笺设计系统

以 Material Components for Android **1.14.0 稳定版**为基础，使用
`Theme.Material3Expressive.DayNight.NoActionBar`。不要重新绘制官方已有组件。

## 信息层级

这是一个设置任务页，不需要底部导航。品牌栏提供位置感；预览让用户理解草稿；
水印编辑是主体；保存是唯一主操作，固定在底部以便滚动、键盘输入时访问。
备份是独立次级操作，用分隔与留白组织，不包裹成另一张大卡片。

- 预览：不对称纸笺轮廓、primaryContainer、强调字级；明确标注为示意。
- 设置：原生 TextInputLayout、MaterialSwitch，空输入是有效的去水印状态。
- 保存：Medium Expressive filled button，使用组件自带的 spring 形变与状态反馈。
- 备份：tonal button、官方 wavy LinearProgressIndicator；失败显示 Error roles 和重试。
- 离开：MaterialAlertDialog，保存、放弃与继续编辑均可达。

## Tokens

- 色彩：`res/values/colors.xml` 与 `values-night/colors.xml` 定义完整品牌浅深色。
  页面通过主题语义 role 取色。Android 12+ 使用官方 DynamicColors 覆盖色彩体系。
  Primary 用于核心操作；Secondary 用于次级操作；Tertiary 仅用于备份说明。
  SurfaceContainerLow/Container/High 分别组织操作区、设置行与更高层次容器。
- 字体：`MaterialStyle.Type` 映射主题的 display/headline/title/body/label/caption。
  不在页面设置独立字号。强调字级用于标题与关键操作。
- 空间：`dimens.xml` 的 4/8/12/16/24/32dp；内容最大宽度 680dp。
- 形状：`Shape.Sujian.*` 的 12/20/28dp、非对称 32dp 纸笺与 full pill。
  官方组件保留 Expressive 状态形状，禁止直接覆盖按钮背景。
- 高度：触控区域至少 48dp；正文、按钮、设置行、操作区均按内容自然增高。
- 动效：按钮沿用 M3E 的 spring tokens；预览留白用主题 duration/easing 的
  ChangeBounds 连续展开收起，无全页闪烁。关闭系统动画时直接切换；进度由官方组件管理。
- 层次：以 surface、空间、字级组织；无渐变与装饰阴影。

## 状态与可访问性

输入保留 80 字上限、计数和清除入口。保存仅在草稿有变化时启用。
输入框的清除图标只修改草稿；「清空水印并保存」明确立即保存。
预览正文不参与 TalkBack，摘要描述实际水印状态。设置开关有完整语义描述。
导出结果使用 polite live region；旋转保留草稿、滚动位置与正在执行的任务，
进程重建后明确提示导出状态未知，不宣称已完成。

任何新增页面先确定主操作与视觉权重，再复用上述 roles、类型与组件。
新增 token 应在这里说明用途。不要在页面添加十六进制颜色、随意字号或新圆角规则。

## 验证

`tests/DesignSmoke.java` 是独立真机 instrumentation；不访问实际便签数据库，
结束后恢复原设置。验证保存、清空、预览、旋转、字体缩放和各语义文字的对比度。
