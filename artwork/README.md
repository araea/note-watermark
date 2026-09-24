# 软件图标

`icon.svg` 是彩色源稿，`icon-monochrome.svg` 是主题单色源稿。修改源稿后生成 Android 矢量资源：

```sh
python scripts/generate-icons.py
python scripts/generate-icons.py --check
```

生成的 `res/` 文件纳入 Git，普通 APK 构建不需要 Python。市场 PNG 从同一 SVG 导出；需要 `puppeteer-core` 与 Chromium：

```sh
CHROMIUM=/path/to/chromium node scripts/render-icon.cjs
```

依赖装在其他目录时，可用 `NODE_PATH` 指向对应的 `node_modules`。
