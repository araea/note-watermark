ColorOS 便签模块：去水印、自定义水印与便签导出。

按 ColorOS 16.0.10、便签 16.7.2 核验，其他版本需实机验证。

## 安装

从[模块市场](https://modules.lsposed.org/module/com.jy.notewatermark/)安装 `NoteWatermark.apk`，在支持 libxposed API 102 的框架中启用，再冷启动便签。作用域固定为 `com.coloros.note`。启用或升级后打开一次「素笺」以同步设置。

## 快速使用

### 分享长图

底部样式三选一：`不显示` 隐藏底部、`留白` 保留约两行空白、`自定义` 替换文字（最多 80 字符）。设置保存后重新打开便签分享页生效。

### 导出

在「素笺」中选择「导出全部便签」。ZIP 保存到系统「下载」目录，按分类导出文本、HTML 与附件。加密便签不导出。

## 限制 / 风险

分享页只接管已识别的 NearMe、ColorOS 和 OPlus 路径。无法可靠识别时不会修改便签。

## 链接

- [源码](https://github.com/araea/note-watermark)