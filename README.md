# note-watermark

<img src="artwork/icon.svg" width="96" height="96" alt="软件图标" />

ColorOS 便签模块：移除或自定义分享长图底部的水印，并把全部便签导出为 ZIP。

按 ColorOS 16.0.10、便签 16.6.22 核验。

## 界面

Material 3 Expressive 风格：强调式标题、双色图标、分组卡片、按压形状变化与波浪导出进度。支持系统深浅色；Android 12 起跟随系统动态配色。

设置页提供实时示意预览、80 字输入计数、未保存提示和离开时的保存选择；旋转屏幕保留草稿与进行中的导出状态。大字体与窄屏可滚动阅读，关闭系统动画时保留静态反馈。

<details>
<summary>查看浅色与深色界面</summary>

<img src="artwork/screenshots/light.png" width="280" alt="便签设置浅色界面" /> <img src="artwork/screenshots/dark.png" width="280" alt="便签设置深色界面" />

</details>

## 安装与使用

1. 安装 APK，在 Xposed 兼容框架中启用模块，并把 `com.coloros.note` 加入作用域。
2. 冷启动便签。
3. 在「便签分享水印」中设置文字，留空表示不显示水印。

## 导出便签

在同一设置页选择「导出全部便签」。ZIP 保存到系统「下载」目录，按便签分类写入文本、HTML 与附件；加密便签不导出，只在导出说明中计数。

## 构建

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
./build.sh
```

首次构建会在 `keystore/` 生成签名密钥，该目录不纳入 Git。更换密钥后，旧版本需先卸载才能安装新包。

## 界面验证

构建并安装模块后，运行 `bash tests/build.sh` 构建独立的真机测试 APK。用同一签名安装测试包，再执行 `am instrument -w com.jy.notewatermark.test/.DesignSmoke`。测试覆盖保存、清空、草稿与导出状态重建、深浅色文字对比度，以及 320dp / 200% 字号布局；不导出真实便签，结束后恢复原设置。

## 许可证

可按 [Apache-2.0](LICENSE-APACHE) 或 [MIT](LICENSE-MIT) 使用。
