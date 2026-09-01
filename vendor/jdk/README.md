# 可携带 JDK 17

把一个 Eclipse Temurin 17 Windows x64 JDK ZIP 放在本目录中。Hub 安装包构建脚本会优先校验并解压这里唯一的 `*.zip`，不要求另一台构建机预先安装 JDK。

当前本地依赖：

- 文件：`OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip`
- 来源：Eclipse Adoptium 官方 Temurin 17 发布
- SHA-256：`e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0`
- 许可证：GPL v2 with Classpath Exception，具体条款随 ZIP 一同提供

JDK ZIP 约 182 MiB，已被 Git 忽略，避免永久放大仓库。需要换电脑打包时复制整个 `vendor\jdk\` 目录；脚本会把它解压到已忽略的 `build\jdk\`。目录中只能放一个 JDK ZIP。
