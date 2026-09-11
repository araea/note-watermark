# note-watermark

ColorOS 便签模块：移除或自定义分享长图底部的水印，并把全部便签导出为 ZIP。

按 ColorOS 16.0.10、便签 16.6.22 核验。

## 安装与使用

1. 安装 APK，在 Xposed 兼容框架中启用模块，并把 `com.coloros.note` 加入作用域。
2. 冷启动便签。
3. 在「便签分享水印」中设置文字，留空表示不显示水印。

## 导出便签

在「便签分享水印」设置页选择「一键导出全部便签」。ZIP 保存到系统「下载」目录，按便签分类写入文本、HTML 与附件；加密便签不导出，只在导出说明中计数。

## 源码

[araea/note-watermark](https://github.com/araea/note-watermark)
