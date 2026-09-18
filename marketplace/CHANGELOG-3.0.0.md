## 3.0.0 · 素笺

### 迁移到现代 Xposed API

- 模块从旧版 `de.robv.android.xposed` 接口迁移到 libxposed API 102（`io.github.libxposed:api:102.0.0`），不再使用 `XposedBridge`、`XposedHelpers` 与 `XC_MethodHook`。
- 入口类改为继承 `XposedModule`，钩子写成拦截器链；字段读取改为普通反射。
- 模块注册改走 APK 内的 `META-INF/xposed/`：入口写 `java_init.list`，属性写 `module.prop`，作用域写 `scope.list`。清单里的 `xposedmodule`、`xposeddescription`、`xposedminversion`、`xposedscope` 已移除，描述改用 `android:description`。
- 作用域固定为 `com.coloros.note`（`staticScope=true`），框架里不需要再手工添加作用域。
- 水印移除、自定义水印、空白占位与导出全部便签的行为与 2.3.2 一致。

升级提示：沿用包名与签名，可直接覆盖升级，已有设置不变。需要支持 libxposed API 102 的框架，LSPosed 2.x 起可用。

验证：真机上框架以现代 API 加载模块（日志标签为 `NoteWatermark`），便签主进程与 `:tbl_privileged_process0` 各装上 3 个钩子，无报错。
