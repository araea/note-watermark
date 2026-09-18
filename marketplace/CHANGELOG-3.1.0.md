## 3.1.0 · 素笺

### 界面升级到 Material 3 Expressive

- 设置页改用官方 Material Components 1.14.0 与 `Theme.Material3Expressive.DayNight.NoActionBar`，自绘的按钮、开关、进度条与对话框换成 Material 组件。
- 建立完整语义色彩层（Primary / Secondary / Tertiary / SurfaceContainer / Error），浅色、深色与 Android 12 起的动态配色都可用，页面里不再有硬编码颜色。
- 形状按层级区分：Small 12dp、Medium 20dp、Large 28dp、预览纸笺 32dp 带非对称切角、pill 用于标签。按钮保留组件自带的 Expressive 形变。
- 字级改用 M3 的 display / headline / title / body / label 主题字级，页面不再单独设字号。
- 预览展开收起改用主题 duration 与 easing 的连续动效，关闭系统动画时直接切换。
- 保存固定在底部，滚动与键盘输入时都能点到；有未保存修改时提示，返回时询问保存、放弃或继续编辑。
- 导出失败改用 Error 配色并给出「重试导出」，导出结果用无障碍实时区域播报。
- 补全无障碍：标题层级、开关语义、预览摘要、48dp 触控区。

构建从手工 javac + d8 + aapt 改为 Gradle（AGP 8.11.1），产物路径与 `build.sh` 用法不变。APK 因为要打进 Material 组件与 AndroidX 资源，体积从几十 KB 增加到约 5.3 MB。

升级提示：沿用包名与签名，可直接覆盖升级，已有设置不变。

验证：真机 instrumentation 覆盖保存、清空、预览、旋转、80 字上限、两倍字号与 320dp 宽度、以及各语义配色的对比度，全部通过。
