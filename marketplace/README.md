ColorOS 便签与笔记模块：去水印、自定义水印与导出。

按 ColorOS 17.0.0.107、笔记 17.1.17、便签 17.0.24 核验，其他版本需实机验证。

## 安装

从[模块市场](https://modules.lsposed.org/module/com.jy.notewatermark/)安装 `NoteWatermark.apk`，在 LSPosed 2.x 或更新的框架中启用，作用域勾选「笔记」（`com.oplus.aipaper`）与「便签」（`com.coloros.note`），再冷启动应用。

## 使用

分享长图时底部样式三选一：`不显示` 隐藏底部、`留白` 保留约两行空白、`自定义` 替换文字（最多 80 字符）。设置保存后重新打开分享页生效。

在「素笺」中选择「导出全部笔记」或「导出全部便签」。ZIP 保存到系统「下载」目录，笔记按笔记库、便签按分类，每条导出文本、HTML 与附件。加密内容不导出。

## 限制 / 风险

分享页只接管已识别的 NearMe、ColorOS 与 OPlus 路径，无法可靠识别时不修改应用。「笔记」里灵感洞察、AI 对话、网页概览等功能的分享图另有水印，不在处理范围。

## 链接

- [源码](https://github.com/araea/note-watermark)
