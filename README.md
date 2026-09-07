note-watermark
==============

[<img alt="github" src="https://img.shields.io/badge/github-araea/note--watermark-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/note-watermark)

去掉或自定义 ColorOS 便签分享长图底部的水印，并一键导出全部便签。

当前按 ColorOS 16.0.10、便签 16.6.22 核验。

## 使用

1. 安装模块，在 vector 启用，作用域勾选便签（`com.coloros.note`）。
2. 冷启动便签。
3. 打开「便签分享水印」设置水印文字；留空即无水印。

## 导出便签

在「便签分享水印」里点「一键导出全部便签」，便签会按分类打包成一个 zip
放进「下载」文件夹：

```
便签导出_20260908_071126.zip
├── 导出说明.txt            导出时间、条数、各分类数量
├── 全部笔记/
│   ├── 001_购物清单.txt    纯文本，开头是标题、分类、时间和标识
│   ├── 001_购物清单.html   便签自带的富文本，用浏览器打开可保留排版
│   └── 001_购物清单_附件/  这条便签的图片等文件
├── 工作/                   自建的分类，名字与便签里一致
├── 速记/
└── 回收站/
```

加密便签不会被导出，只在「导出说明.txt」里计数。

便签的数据库只有便签自己读得到，所以导出是在注入进便签进程的代码里做的：
设置页查询便签自带的 `com.oneplus.provider.Note`，模块的 hook 截下这次查询，
在便签进程里把 zip 通过 MediaStore 写进「下载」文件夹，再把结果带回设置页。
便签没在运行时，这次查询会顺带把它拉起来。万一这条路走不通，设置页会记下
请求并打开便签，hook 在便签启动时补做导出，完成后弹一条提示。

## 构建

```sh
./build.sh
```

克隆后下载 R8：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
```

首次构建会在 `keystore/` 生成签名密钥，之后每次都用它签名。这个目录不进
仓库，请自行备份：换了密钥，装过旧版本的人必须先卸载才能升级。

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
