# peijian（配件管理系统）

> Android 应用（Kotlin），项目刚刚起步，代码即将加入。

## 技术栈（与 saoma 项目保持一致的工程约定）

- 语言 / UI：Kotlin + Jetpack Compose
- 本地数据：Room（`app/schemas/**` 必须入库）
- 构建：Gradle Kotlin DSL（`build.gradle.kts`）

## 仓库

- 远程仓库：<https://github.com/qudingshun/peijian>（Private）
- 默认分支：`main`
- 提交规范参考 `E:\saoma` 项目：`<type>: <摘要>`

## 本地开发

```powershell
git clone https://github.com/qudingshun/peijian.git
cd peijian
gradlew.bat assembleDebug
```

## 约定

- 文本文件统一 LF + UTF-8（见 `.gitattributes`）
- 签名文件、密钥文件、本地部署目录不入库（见 `.gitignore`）
- `local.properties` 与 `keystore.properties` 由各机器本地维护，不提交
