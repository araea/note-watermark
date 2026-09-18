# 素笺

ColorOS 便签模块：移除或自定义分享长图底部的水印，并把全部便签导出为 ZIP。

按 ColorOS 16.0.10、便签 16.6.22 核验。

## 运行条件

- ColorOS 便签 `com.coloros.note`
- 支持 libxposed API 102 的框架（LSPosed 2.x 起）

## 安装

1. 安装 `NoteWatermark.apk`
2. 在框架中启用模块。作用域由模块固定为 `com.coloros.note`，不需要手工添加
3. 冷启动便签
4. 打开「素笺」，设置水印文字。留空表示不显示水印

## 导出便签

在「素笺」设置页选择「导出全部便签」。ZIP 保存到系统「下载」目录，按便签分类写入文本、HTML 与附件。加密便签不导出，只在导出说明中计数。

## 源码

[araea/note-watermark](https://github.com/araea/note-watermark)
