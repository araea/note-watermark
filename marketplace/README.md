# 素笺

ColorOS 便签模块：去水印、自定义水印与便签导出。

已按 ColorOS 16.0.10、便签 16.7.2 核验；其他版本需实机验证。支持的分享页面和运行条件见[项目说明](https://github.com/araea/note-watermark#运行条件)。

## 安装

从[模块市场](https://modules.lsposed.org/module/com.jy.notewatermark/)安装 `NoteWatermark.apk`，在支持 libxposed API 102 的框架中启用，再冷启动便签。模块作用域固定为 `com.coloros.note`。启用或升级后，打开一次「素笺」以同步设置。

## 分享长图

选择隐藏底部、保留空白或显示自定义文字（最多 80 个字符）。设置保存后，重新打开便签分享页生效。

## 导出

在「素笺」中选择「导出全部便签」。ZIP 保存到系统「下载」目录，包含按分类整理的文本、HTML 和附件。加密便签不导出。

## 源码

[araea/note-watermark](https://github.com/araea/note-watermark)
