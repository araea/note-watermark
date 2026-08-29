note-watermark
==============

[<img alt="github" src="https://img.shields.io/badge/github-araea/note--watermark-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/note-watermark)

去掉 ColorOS 便签分享长图底部的「ColorOS 便签」水印，也可以快速换成自己的字。

当前按 ColorOS 16.0.10、便签 16.6.22 核验。

## 阅读前

需要先了解这些词，不熟的请自行查阅：

- **root / Zygisk**：手机已取得最高权限，并能在 App 启动时注入
- **Xposed / LSPosed / vector**：往正在运行的 App 里挂钩子；本仓库用的是 vector
- **作用域 (scope)**：注入进哪一个 App，这里是 ColorOS 便签
- **冷启动**：杀掉便签再打开，模块才会进入新进程

## 使用

1. 准备已 root、带 Zygisk 的安卓，以及兼容 LSPosed API 的框架。
2. 构建并安装模块，将便签（`com.coloros.note`）加入作用域。
3. 冷启动便签。
4. 点击桌面的「便签分享水印」图标，直接输入水印并保存；留空就是空水印。
5. 冷启动便签，分享笔记为图片查看效果。

设置页还提供「空水印时保留底部留白」开关，默认开启。它会隐藏水印文字和分隔线，但保留原水印区域作为约两行高的底部间距；不会在原笔记末尾写入换行。关闭后会彻底折叠水印区域。

旧版的 `watermark.txt` 仍作为设置读取失败时的兼容后备，只取第一行。路径按顺序读取，命中第一个可读的非空文件即停：

- `/sdcard/Android/data/com.coloros.note/files/watermark.txt`
- `/sdcard/note_watermark.txt`
- `/sdcard/Download/note_watermark.txt`

改完设置后重新打开分享页即可，不必重启手机或重装模块。

## 构建

在 Termux 里执行 `./build.sh`。需要 OpenJDK、aapt、zipalign、apksigner，以及 `android.jar` 与 R8。路径写在脚本里，按本机环境改。

`libs/r8.jar` 被 gitignore。克隆后下载一次：

```
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
```

## 注意事项

- 这是官方便签的外壳，不是无 root 方案。
- 便签换版本必须重新核对接口，不能沿用上一版的假设。
- 想停手：从作用域里移除便签。

## 致谢

- [vector](https://github.com/JingMatrix/LSPosed) - Xposed 框架

## QQ 群

- 956758505

<br>

#### License

<sup>
Licensed under either of <a href="LICENSE-APACHE">Apache License, Version
2.0</a> or <a href="LICENSE-MIT">MIT license</a> at your option.
</sup>

<br>

<sub>
Unless you explicitly state otherwise, any contribution intentionally submitted
for inclusion in this crate by you, as defined in the Apache-2.0 license, shall
be dual licensed as above, without any additional terms or conditions.
</sub>
