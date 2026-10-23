# 素笺（note-watermark）

ColorOS 便签与笔记模块：去水印、自定义水印与导出。

[![GitHub](https://img.shields.io/badge/GitHub-araea%2Fnote--watermark-181717?logo=github&logoColor=white)](https://github.com/araea/note-watermark)
[![Xposed Modules Repo](https://img.shields.io/badge/Xposed%20Modules%20Repo-com.jy.notewatermark-2ea44f)](https://github.com/Xposed-Modules-Repo/com.jy.notewatermark)

## 安装

模块只在[模块市场](https://modules.lsposed.org/module/com.jy.notewatermark/)发布。运行条件：

- 框架：LSPosed 2.x 或更新（libxposed API 102）
- 便签：`com.coloros.note`
- 笔记：`com.oplus.aipaper`

从市场安装 `NoteWatermark.apk`，在框架中启用模块，作用域勾选上面两个包，再冷启动对应应用。两个应用装了哪个就作用于哪个。

## 使用

### 分享长图

底部样式三选一，两个应用共用，重新打开分享页后生效：

- `不显示`：隐藏底部区域，长图到正文为止
- `留白`：隐藏水印，保留约两行高度的空白
- `自定义`：保留分隔线并替换文字，最多 80 个字符

页面预览使用相同规则。切换样式不会清除已保存的自定义文字。「笔记」页脚右侧的「记录人」是笔记自己的署名，不属于水印，`自定义` 时保留。

### 导出

在「素笺」中选择「导出全部笔记」或「导出全部便签」。ZIP 保存到系统「下载」目录。加密内容不导出，只在结果中计数。

笔记按笔记库分目录，嵌套的笔记库保留层级，另有 `未分类` 与 `回收站`：

```text
笔记导出_20261018_110121.zip
├─ 导出说明.txt
└─ <笔记库>/<子笔记库>/
   ├─ 001_<标题>.txt        标题、笔记库、标签、时间与纯文本
   ├─ 001_<标题>.html       带排版和图片的页面；引用其他笔记的地方是链接
   └─ 001_<标题>_附件/      这条笔记的图片等文件
```

便签按分类导出同样的文件，ZIP 名为 `便签导出_…`。

## 限制 / 风险

- 按 ColorOS 17.0.0.107、笔记 17.1.17、便签 17.0.24 核验，其他版本需实机验证。
- 分享页只接管已识别的 NearMe、ColorOS 与 OPlus 路径。无法可靠识别时不修改应用。
- 「笔记」里灵感洞察、AI 对话、网页概览等功能的分享图另有水印，不在处理范围；AI 生成内容的标识也不处理。
- 导出「笔记」需要借用应用自带的 zstd 解码器读取正文。解码器不可用或某条正文无法解析时，该条仍会导出，只有纯文本，并在 `导出说明.txt` 里计数。

## 链接

- [模块市场](https://modules.lsposed.org/module/com.jy.notewatermark/)
- [MIT](LICENSE-MIT) / [Apache-2.0](LICENSE-APACHE)
