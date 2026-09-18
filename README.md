# 素笺

ColorOS 便签模块：移除或自定义分享长图底部的水印，并把全部便签导出为 ZIP。

按 ColorOS 16.0.10、便签 16.6.22 核验。

## 运行条件

- ColorOS 便签 `com.coloros.note`
- 支持 libxposed API 102 的框架（LSPosed 2.x 起）

## 安装

1. 安装市场发布的 `NoteWatermark.apk`
2. 在框架中启用模块。作用域由模块固定为 `com.coloros.note`，不需要手工添加
3. 冷启动便签
4. 打开「素笺」，设置水印文字。留空表示不显示水印

## 导出便签

在同一设置页选择「导出全部便签」。ZIP 保存到系统「下载」目录，按便签分类写入文本、HTML 与附件。加密便签不导出，只在导出说明中计数。

## 构建与测试

需要 JDK 17 以上与 Android SDK（platform 35、build-tools 35.0.0），SDK 路径写进 `local.properties` 的 `sdk.dir`。Termux 下 `build.sh` 会改用系统 `aapt2`。

```sh
./build.sh
```

产物为 `build/NoteWatermark.apk`。模块按 `com.google.android.material:material:1.14.0` 与 `io.github.libxposed:api:102.0.0` 构建，libxposed 依赖只用于编译，不打进 APK。首次构建会在 `keystore/` 生成签名密钥，该目录不纳入 Git。更换密钥后，旧版本需先卸载才能安装新包。

真机测试包另外需要 R8：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
bash tests/build.sh
```

用同一签名安装后执行 `am instrument -w com.jy.notewatermark.test/.DesignSmoke`。测试不导出真实便签，结束后恢复原设置，并把浅色、深色与大字号三组界面截图写到应用私有目录。

## 许可证

可按 [Apache-2.0](LICENSE-APACHE) 或 [MIT](LICENSE-MIT) 使用。
