# 自动点击器高级版 (`com.xundao.clicker`) 编译指南

由于已预置阿里云国内 Maven 镜像源与 GitHub Actions 工作流，你可以通过以下任一方式快速获取可安装的 `.apk` 文件：

## 方式一：免安装环境，用 GitHub Actions 云端免费自动编译（最省事）
1. 在 GitHub 新建一个私有或公开仓库，将解压后的工程文件全部上传（确保包含 `.github/workflows/build_apk.yml`）。
2. 打开仓库顶部的 **Actions** 标签页，点击 **Build Signed APK (com.xundao.clicker)** -> **Run workflow**。
3. 等待约 2~3 分钟编译完成后，在页面底部的 **Artifacts** 区域即可直接下载已签名的 `com.xundao.clicker_v1.0.0_Pro_Signed.apk` 与 `app-debug.apk`。

## 方式二：使用 Android Studio 本地一键编译
1. 解压本压缩包，使用 Android Studio 打开 `XundaoAutoClickerPro` 文件夹（已在 `settings.gradle.kts` 中配置好阿里云镜像加速）。
2. 等待 Gradle Sync 完成（确保 Gradle JDK 选择 **JDK 17**）。
3. 点击顶部菜单 **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**，生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 方式三：Windows / Linux 命令行一键脚本
- **Windows**：双击运行工程根目录下的 `build_apk.bat`。
- **Linux / macOS**：终端执行 `chmod +x build_apk.sh && ./build_apk.sh`。
