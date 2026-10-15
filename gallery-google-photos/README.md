# Gallery Google Photos（相册显示 Google 相册照片）

目标：在 HyperOS 4（REDMI K100 PRO MAX）上，让打开系统相册（`com.miui.gallery`）就能看到 /
使用 Google Photos 云照片，即恢复「相册云备份供应商 → Google 相册」能力。

## 一、结论：你的 fork 没改过相册部分

对比对象：你 fork 的 `/workspace` 与官方源 `ReChronoRain/HyperCeiler`（HEAD `861be04`）。

- `library/libhook/.../rules/gallery/` 整个目录：**完全一致**
- `library/libhook/.../app/Gallery.java`：**完全一致**
- `library/core/src/main/res/xml/gallery.xml`：**完全一致**

也就是说，仓库里的「相册云备份供应商 → Google 相册」功能，就是官方原版逻辑，你没做任何二次修改。
（你 fork 唯一 commit 是 `7ea5aba "1"`，相对官方还缺很多后续的 HyperOS4 适配，但跟相册无关。）

## 二、官方这个功能到底做了什么

入口在 `gallery.xml` 的 `gallery_backup_server` 下拉框，取值 `0/1/2/3` = 默认 / 小米云 / Google / Google+OneDrive。

选择 Google（=2）后，[ChangeBackupServer.java](https://github.com/ReChronoRain/HyperCeiler) 只做了一件事：

```java
findAndHookMethod("com.miui.gallery.transfer.GoogleSyncHelper", "isCloudServiceOffLine",
    before: param.setResult(true));
// 类不存在时 fallback 到 com.miui.gallery.util.BuildUtil.isGlobal
```

它只是把「Google 云服务是否在线」这个 flag 强行改成 `true`，从而在国际版相册里把 Google 备份入口放出来。
它**并不会去同步 / 拉取 Google Photos 的照片数据**，真正的同步逻辑在 Gallery 内部，依赖 GMS 与 Google 账号。

## 三、为什么在 HyperOS 4 国行上「不行」

最可能的原因是国行 `com.miui.gallery` 的 APK 里根本没有完整的 Google Photos 云同步实现：

1. `com.miui.gallery.transfer.GoogleSyncHelper` 类可能被裁剪（hook 找不到类 / 命中 fallback）；
2. 即便 `BuildUtil.isGlobal` 被改成 true，如果没有后续的 Google 账号绑定、同步任务、云相册数据源，
   UI 上也不会凭空出现 Google Photos 的照片；
3. 设备可能缺少可用 GMS（Google Play 服务）或未登录 Google 账号。

> 结论：靠改一个布尔值救不回来。要真正实现，需要逆向你这台机上的真实 Gallery APK。

## 四、前置条件（需要你在真机上确认）

- [ ] 已解锁 BL + root，LSPosed 可用（Xposed 路线硬前提）
- [ ] 已安装且能正常登录 Google 服务（GMS / Google 账号），能访问 photos.google.com
- [ ] 能从设备拉取 `com.miui.gallery` 的 APK（`adb shell pm path com.miui.gallery`）用于反编译
- [ ] 本地有 HyperCeiler 编译环境（或直接用本 fork 的 Gradle 构建）

## 五、路线图

### 阶段 0：确认环境
1. 确认 LSPosed 已作用域到 `com.miui.gallery`。
2. 确认 GMS / Google 账号可用。
3. 拉取 APK：`adb shell pm path com.miui.gallery`，`adb pull` 到电脑。

### 阶段 1：诊断（用本目录的增强版 hook）
1. 把 `ChangeBackupServer.java` 覆盖到
   `library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/gallery/ChangeBackupServer.java`
2. 重新编译安装，打开模块里「相册 → 备份服务提供商 → Google」，重启相册作用域。
3. 看日志：`adb logcat -s HyperCeilerGallery`。
   重点看这些类存在与否：
   - `com.miui.gallery.transfer.GoogleSyncHelper`
   - `com.miui.gallery.util.BuildUtil`
   - `com.miui.gallery.util.BaseBuildUtil`
   根据输出判断是「类被裁剪」还是「类在但方法名变了 / 逻辑变了」。

### 阶段 2：逆向 APK（关键，必须在真机 APK 上做）
1. 反编译 Gallery APK（jadx / JADX-GUI）。
2. 搜 `Google`、`Backup`、`Cloud`、`Transfer`、`sync` 等关键词，找到：
   - 云备份「供应商」如何判定（是否支持 Google Photos / OneDrive）；
   - Google Photos 云相册的数据源 / 账号绑定 / 列表加载入口。
3. 记录真实的类名、方法名和方法签名（这是国行 HyperOS 4 与官方假设的最大差异点）。

### 阶段 3：写正确的 hook
根据阶段 2 的真实类名/方法名，把 `hookGoogleBackup` 里的候选类与方法替换为真实值，
必要时补上「强制开启 Google 备份入口」+「注入云相册数据源」两段 hook。

### 阶段 4：打包验证
1. 编译并安装，`adb logcat` 观察无异常。
2. 打开相册，确认出现 Google Photos 云相册 / 照片。

## 六、注意

- 「打开相册就看到 Google Photos 照片」比官方功能要求更高：不只是放出入口，还要让相册真的去读
  Google Photos 的数据。如果国行 APK 把整个 Google Photos 数据源都删了，Xposed 方案可能需要
  自己实现一个数据源（工作量很大），此时更现实的替代是「独立相册 App + Google Photos Library API」。
- 本目录目前只含「阶段 1」的增强版 hook，用于把失败原因从"黑盒"变成"可读的日志"。
  拿到阶段 2 的真实类名后，再继续补阶段 3 的 hook。