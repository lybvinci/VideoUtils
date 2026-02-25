# VideoUtils / 视频小工具

An Android utility app to extract audio from videos, with batch processing, selectable output directory, and system-following light/dark theme.

一个用于从视频中提取音频的 Android 小工具，支持批量转换、可配置输出目录、并支持跟随系统的深浅色主题。

---

## Features / 功能

- Batch convert multiple video files / 多选视频批量转换
- Output formats: prefer M4A, fallback to MP3 when M4A fails / 输出格式：优先 M4A，M4A 失败时尝试 MP3
- Output directory:
  - Default public directory (Documents/VideoUtils or Pictures/VideoUtils) / 默认公共目录（Documents/VideoUtils 或 Pictures/VideoUtils）
  - Custom folder via Storage Access Framework / 通过 SAF 选择自定义目录
- Output path is selectable for copy / 输出路径可选中复制
- Material 3 neutral design + light/dark theme (follow system by default) / Material 3 中性设计 + 深浅色主题（默认跟随系统）
- Multi-language UI (Chinese + English) / 中英文双语

---

## Requirements / 环境要求

- Android Studio (recommended) / 推荐使用 Android Studio
- JDK 17 (AGP 8.x requires Java 17) / 需要 JDK 17（AGP 8.x 要求）
- Android SDK: compileSdk 36, targetSdk 35, minSdk 23 / compileSdk 36，targetSdk 35，minSdk 23

---

## Build & Run / 构建与运行

### Android Studio / Android Studio

- Open this project in Android Studio / 用 Android Studio 打开工程
- Sync Gradle and run `app` / 同步 Gradle 并运行 `app`

### Command line / 命令行

Use Android Studio bundled JBR (JDK 17) to avoid JDK mismatch. On macOS:

使用 Android Studio 自带的 JBR（JDK 17）来避免 JDK 版本不匹配。macOS 示例：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleDebug --no-daemon
```

---

## Release Signing / 发布签名

This project reads release signing info from environment variables to avoid committing secrets.

工程通过环境变量读取 release 签名信息，避免把证书/密码提交到仓库。

### Environment variables / 环境变量

- `VIDEO_UTILS_STORE_FILE` (keystore path / keystore 路径)
- `VIDEO_UTILS_STORE_PASSWORD`
- `VIDEO_UTILS_KEY_ALIAS`
- `VIDEO_UTILS_KEY_PASSWORD`

### Build release / 打包 release

```bash
export VIDEO_UTILS_STORE_FILE="/abs/path/your_release.keystore"
export VIDEO_UTILS_STORE_PASSWORD="******"
export VIDEO_UTILS_KEY_ALIAS="your_alias"
export VIDEO_UTILS_KEY_PASSWORD="******"

JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleRelease :app:bundleRelease --no-daemon
```

Outputs / 产物：

- APK: `app/build/outputs/apk/release/app-release.apk`
- AAB: `app/build/outputs/bundle/release/app-release.aab`

---

## GitHub Actions (CI) / GitHub 自动打包

This repo includes a GitHub Actions workflow that builds APKs on every push and PR, and uploads them as artifacts.

仓库已内置 GitHub Actions 工作流：每次 push / PR 会自动构建 APK，并以 artifact 形式上传供下载。

- Workflow file / 工作流文件：`.github/workflows/android.yml`
- Where to download / 下载位置：GitHub → Actions → 选择一次运行 → Artifacts

### Debug APK / Debug 包

Debug APK does not need a release keystore. It is always built and uploaded.

Debug 包不需要 release 证书，会始终构建并上传。

### Release APK/AAB (optional) / Release 包（可选）

Release artifacts are built only when signing secrets are provided.

只有在配置了签名相关 Secrets 后才会构建并上传 Release APK/AAB。

Add these repository secrets / 在仓库 Secrets 中添加：

- `VIDEO_UTILS_KEYSTORE_BASE64` (base64 of your keystore file / keystore 文件的 base64)
- `VIDEO_UTILS_STORE_PASSWORD`
- `VIDEO_UTILS_KEY_ALIAS`
- `VIDEO_UTILS_KEY_PASSWORD`

Generate base64 on macOS / macOS 生成 base64：

```bash
base64 -i /abs/path/your_release.keystore | pbcopy
```

---

## License / 许可证

MIT License. See [LICENSE](./LICENSE).
