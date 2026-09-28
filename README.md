ColorOS 便签模块：去水印、自定义水印与便签导出。

已按 ColorOS 16.0.10、便签 16.7.2 核验。其他版本需实机验证。分享页只接管已识别的 NearMe、ColorOS 和 OPlus 路径；无法可靠识别时不会修改便签。

## 安装

模块仅在[模块市场](https://modules.lsposed.org/module/com.jy.notewatermark/)发布。

运行条件：

- 便签应用：`com.coloros.note`
- 框架：支持 libxposed API 102（LSPosed 2.x 或更新）

从模块市场安装 `NoteWatermark.apk`，在框架中启用模块，再冷启动便签。作用域固定为 `com.coloros.note`，不需要手动添加。打开「素笺」确认模块已生效，并将设置同步给便签。

升级后需再次打开「素笺」，让便签读取设置。ColorOS 不允许便签主动启动素笺。

## 分享长图

底部样式三选一，重新打开便签分享页后生效：

- `不显示`：隐藏底部区域。
- `留白`：隐藏水印，保留约两行高度的空白。
- `自定义`：保留分隔线并替换文字，最多 80 个字符。

页面预览使用相同规则。切换样式不会清除已保存的自定义文字。

## 导出

在「素笺」中选择「导出全部便签」。ZIP 保存到系统「下载」目录，按分类导出文本、HTML 和附件。加密便签不会导出，只在结果中计数。

## 配置

设置通过 ContentProvider 读写，权限 `com.jy.notewatermark.settings`。

| 键 | 含义 |
| --- | --- |
| `watermark_text` | 自定义水印文字，上限 80 字符 |
| `keep_blank_space` | 留白模式开关 |
| `selected_mode` | 界面选择的样式（`不显示` / `留白` / `自定义`），仅 UI 使用 |
| `last_custom_text` | 自定义文字的暂存值 |
| `export_request` | 触发导出请求 |

注入的钩子只读取 `watermark_text` 与 `keep_blank_space`。

## 构建

需要 JDK 17 以上、Android SDK platform 35、build-tools 35.0.0。将 SDK 路径写入 `local.properties` 的 `sdk.dir`；Termux 下 `build.sh` 使用系统 `aapt2`。

```sh
./build.sh
```

APK 输出到 `build/NoteWatermark.apk`。首次构建在 `keystore/` 生成签名密钥，该目录不入库。备份密钥和每版 APK；更换密钥后需先卸载旧版。

构建环境：

- Gradle 8.13（wrapper），Android Gradle Plugin 8.11.1
- `compileSdk` 35，`minSdk` 26，`targetSdk` 34
- 依赖 `io.github.libxposed:api:102.0.0`、`com.google.android.material:material:1.14.0`
- `versionCode` 18，`versionName` 4.1.1

模块以 libxposed API 102 注册，入口类 `com.jy.notewatermark.Main`。注册文件位于 `resources/META-INF/xposed/`：

- `module.prop`：`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`
- `scope.list`：`com.coloros.note`
- `java_init.list`：`com.jy.notewatermark.Main`

旧式 `xposedmodule` / `xposedminversion` 等 `meta-data` 已不再使用。

## 测试

真机设计测试还需下载 R8：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
bash tests/build.sh
am instrument -w com.jy.notewatermark.test/.DesignSmoke
```

测试不导出真实便签；完成后恢复原设置。

## 发布

正式发布只通过[模块市场](https://modules.lsposed.org/module/com.jy.notewatermark/)。发布脚本位于 `marketplace/publish.sh`。界面规范见[设计系统](artwork/DESIGN.md)。

## 许可证

可按 [Apache-2.0](LICENSE-APACHE) 或 [MIT](LICENSE-MIT) 使用。
