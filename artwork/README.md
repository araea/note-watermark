# 软件图标

`icon.svg` 是彩色源稿，`icon-monochrome.svg` 是主题单色源稿。路径按 Android 的 108 × 108 坐标绘制。

编辑源稿后运行：

```sh
python scripts/generate-icons.py
python scripts/generate-icons.py --check
```

生成的 `res/` 矢量资源纳入 Git，普通 APK 构建无需 Python 或图像依赖。基础图标用于兼容读取传统资源的界面。v26 资源提供自适应前景/背景，v33 增加单色层。清单的 `icon` 与 `roundIcon` 引用同一套资源。`ic_toolbar_logo` 是设置页顶栏用的 28dp 标记，取同一前景字形，裁掉外边距与背景色角块。

市场使用同一 SVG 导出的 512 × 512 透明 PNG。准备 `puppeteer-core` 与 Chromium 后运行：

```sh
CHROMIUM=/path/to/chromium node scripts/render-icon.cjs
```

依赖装在别处时用 `NODE_PATH` 指向其 `node_modules`。导出只加载本地 SVG，不访问网页。
