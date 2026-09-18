# 素笺

ColorOS 便签模块：移除或自定义分享长图底部的水印，并把全部便签导出为 ZIP。

按 ColorOS 16.0.10、便签 16.6.22 核验。

## 界面

Material 3 Expressive 风格，支持系统深浅色，Android 12 起跟随系统动态配色。设置页提供实时示意预览、80 字输入计数与未保存提示。旋转屏幕时保留草稿与进行中的导出状态。

<details>
<summary>查看浅色与深色界面</summary>

<img src="https://raw.githubusercontent.com/araea/note-watermark/main/artwork/screenshots/light.png" width="280" alt="便签设置浅色界面" /> <img src="https://raw.githubusercontent.com/araea/note-watermark/main/artwork/screenshots/dark.png" width="280" alt="便签设置深色界面" />

</details>

## 运行条件

- ColorOS 便签 `com.coloros.note`
- 支持 libxposed API 102 的框架（LSPosed 2.x 起）

## 安装

1. 安装 APK，在框架中启用模块。作用域由模块固定为 `com.coloros.note`，不需要手工添加
2. 冷启动便签
3. 打开「素笺」，设置水印文字。留空表示不显示水印

## 导出便签

在「素笺」设置页选择「导出全部便签」。ZIP 保存到系统「下载」目录，按便签分类写入文本、HTML 与附件。加密便签不导出，只在导出说明中计数。

## 源码

[araea/note-watermark](https://github.com/araea/note-watermark)
