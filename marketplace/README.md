# note-watermark

ColorOS 便签模块：移除或自定义分享长图底部的水印，并把全部便签导出为 ZIP。

按 ColorOS 16.0.10、便签 16.6.22 核验。

## 界面

Material 3 Expressive 风格：强调式标题、双色图标、分组卡片、按压形状变化与波浪导出进度。支持系统深浅色；Android 12 起跟随系统动态配色。

设置页提供实时示意预览、80 字输入计数、未保存提示和离开时的保存选择；旋转屏幕保留草稿与进行中的导出状态。大字体与窄屏可滚动阅读，关闭系统动画时保留静态反馈。

<details>
<summary>查看浅色与深色界面</summary>

<img src="https://raw.githubusercontent.com/araea/note-watermark/main/artwork/screenshots/light.png" width="280" alt="便签设置浅色界面" /> <img src="https://raw.githubusercontent.com/araea/note-watermark/main/artwork/screenshots/dark.png" width="280" alt="便签设置深色界面" />

</details>

## 安装与使用

1. 安装 APK，在 Xposed 兼容框架中启用模块，并把 `com.coloros.note` 加入作用域。
2. 冷启动便签。
3. 在「便签分享水印」中设置文字，留空表示不显示水印。

## 导出便签

在「便签分享水印」设置页选择「导出全部便签」。ZIP 保存到系统「下载」目录，按便签分类写入文本、HTML 与附件；加密便签不导出，只在导出说明中计数。

## 源码

[araea/note-watermark](https://github.com/araea/note-watermark)
