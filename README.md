note-watermark
==============

[<img alt="github" src="https://img.shields.io/badge/github-araea/note--watermark-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/note-watermark)

去掉或自定义 ColorOS 便签分享长图底部的水印。

当前按 ColorOS 16.0.10、便签 16.6.22 核验。

## 使用

1. 安装模块，在 vector 启用，作用域勾选便签（`com.coloros.note`）。
2. 冷启动便签。
3. 打开「便签分享水印」设置水印文字；留空即无水印。

## 构建

```sh
./build.sh
```

克隆后下载 R8：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
```

## QQ 群

956758505

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
