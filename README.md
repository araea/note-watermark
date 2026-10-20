# 素笺（note-watermark）

ColorOS 便签与笔记模块：去水印、自定义水印与导出

[![GitHub](https://img.shields.io/badge/GitHub-araea%2Fnote--watermark-181717?logo=github&logoColor=white)](https://github.com/araea/note-watermark)
[![Xposed Modules Repo](https://img.shields.io/badge/Xposed%20Modules%20Repo-com.jy.notewatermark-2ea44f)](https://github.com/Xposed-Modules-Repo/com.jy.notewatermark)

## 安装

模块仅在[模块市场](https://modules.lsposed.org/module/com.jy.notewatermark/)发布。运行条件：

- 笔记应用：`com.oplus.aipaper`（ColorOS 17 新增的 AI 笔记）
- 便签应用：`com.coloros.note`
- 框架：支持 libxposed API 102（LSPosed 2.x 或更新）

两个应用装了哪个就作用于哪个。从模块市场安装 `NoteWatermark.apk`，在框架中启用模块，再冷启动对应应用。作用域为上面两个包。

从 4.1.1 及更早版本升级时，框架不会自动加入「笔记」：打开「素笺」，点「笔记」那一行的「未加入作用域」向框架申请，或在框架里手动勾选。

## 快速使用

### 分享长图

底部样式三选一，两个应用共用，重新打开分享页后生效：

- `不显示`：隐藏底部区域，长图到正文为止
- `留白`：隐藏水印，保留约两行高度的空白
- `自定义`：保留分隔线并替换文字，最多 80 个字符

页面预览使用相同规则。切换样式不会清除已保存的自定义文字。

「笔记」页脚右侧的「记录人」是笔记自己的署名，不属于水印，`自定义` 时保留。

### 导出

在「素笺」中选择「导出全部笔记」或「导出全部便签」。ZIP 保存到系统「下载」目录。加密内容不会导出，只在结果中计数。

笔记按笔记库分目录，嵌套的笔记库保留层级，另有 `未分类` 与 `回收站`：

```
笔记导出_20261018_110121.zip
├─ 导出说明.txt
└─ <笔记库>/<子笔记库>/
   ├─ 001_<标题>.txt        标题、笔记库、标签、时间与纯文本
   ├─ 001_<标题>.html       带排版和图片的页面；引用其他笔记的地方是链接
   └─ 001_<标题>_附件/      这条笔记的图片等文件
```

便签按分类导出同样的文件，ZIP 名为 `便签导出_…`。

### 更新

从 4.3.0 起，「素笺」自己检查更新：打开时与每天一次，查看 LSPosed 模块仓库里本模块的最新正式版（仓库页暂时连不上时改查 GitHub 上的同一份发布）。有新版本时，设置页顶部出现一张卡片，写明版本、改动要点、大小与发布日期，「更新内容」可读完整说明。

点「立即更新」后，素笺下载安装包（先走模块仓库的下载镜像，失败再走 GitHub），核对包名、版本号与签名都与当前一致，再交给系统安装。第一次需要在系统设置里允许「素笺」安装应用，允许后回到设置页会自动继续。安装完成后重新启动笔记和便签，新版本才会接管分享。

后台检查只在发现新版本时发一条通知，同一版本只发一次；页面上已经看到的版本不再通知。「自动检查更新」可在设置页底部关闭，关闭后仍可点版本那一行手动检查。素笺联网只为检查和下载更新，不上传任何内容。

## 配置

设置通过 ContentProvider 读写，权限 `com.jy.notewatermark.settings`。另外同一份设置写在框架的远程偏好（组 `footer`），钩子优先读它，这样应用没在运行时设置也能送达。

| 键 | 含义 |
| --- | --- |
| `watermark_text` | 自定义水印文字，上限 80 字符 |
| `keep_blank_space` | 留白模式开关 |
| `selected_mode` | 界面选择的样式（`不显示` / `留白` / `自定义`），仅 UI 使用 |
| `last_custom_text` | 自定义文字的暂存值 |
| `export_request` | 触发导出请求 |
| `export_target` | 导出请求针对的应用包名 |

注入的钩子只读取 `watermark_text` 与 `keep_blank_space`。

更新相关的状态单独存在应用的 `update` 偏好里（自动检查开关、最近一次检查结果、已通知过的版本），不经 ContentProvider 暴露。

## 限制 / 风险

按 ColorOS 17.0.0.107、笔记 17.1.17、便签 17.0.24 核验，其他版本需实机验证。

分享页只接管已识别的 NearMe、ColorOS 和 OPlus 路径。无法可靠识别时不会修改应用。

「笔记」里灵感洞察、AI 对话、网页概览等功能的分享图另有水印，不在处理范围；AI 生成内容的标识也不处理。

导出「笔记」时需要借用应用自带的 zstd 解码器读取正文。解码器不可用或某条正文无法解析时，该条仍会导出，只有纯文本，并在 `导出说明.txt` 里计数。

## 链接

- [模块市场](https://modules.lsposed.org/module/com.jy.notewatermark/)
- [MIT](LICENSE-MIT) / [Apache-2.0](LICENSE-APACHE)
