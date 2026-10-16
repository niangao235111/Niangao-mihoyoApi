# UIGF SDK 固定版本

来源：[UIGF-org/mihoyo-api-sdk](https://github.com/UIGF-org/mihoyo-api-sdk)，提交 `7c3107f95b72e7324af966b2e8989eb8375e5fc7`。

沿用旧项目已构建的 Java SDK JAR，坐标 `io.clientcore:uigf-mihoyo-checkin:1.0.0-beta.1`。maven 目录是标准文件仓库，含 POM、JAR 和 SHA-1 校验；运行依赖 ClientCore 在主 pom 中显式声明。原始许可证见项目 licenses/UIGF-SDK-LICENSE.txt。

此依赖目前未从公共 Maven Central 获取，因此保留在项目内，确保 IDEA 和 Maven 在新机器上均能解析。升级时应重新核对 SDK 模型与原始 JSON 兼容行为，并运行模拟上游测试。
