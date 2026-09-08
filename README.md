# note-watermark

<img src="artwork/icon.svg" width="96" height="96" alt="软件图标" />

ColorOS 便签模块：移除或自定义分享长图底部的水印，并将全部便签导出为 ZIP。

当前按 ColorOS 16.0.10、便签 16.6.22 核验。

## 安装与使用

1. 安装 APK，在 Xposed 兼容框架中启用模块，并将 com.coloros.note 加入作用域。
2. 冷启动便签。
3. 在「便签分享水印」中设置文字；留空表示不显示水印。

## 导出便签

在同一设置页选择「一键导出全部便签」。ZIP 会保存到系统「下载」目录，按便签分类
写入文本、HTML 和附件。加密便签不会导出，只在导出说明中计数。

## 构建

~~~
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
./build.sh
~~~

首次构建会在 keystore/ 生成签名密钥。该目录不纳入 Git；更换密钥后，旧版本需先卸载
才能安装新包。

## 许可证

可按 [Apache-2.0](LICENSE-APACHE) 或 [MIT](LICENSE-MIT) 使用。
