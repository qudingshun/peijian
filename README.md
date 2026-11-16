# peijian（售后配件管理 · PDA + 电脑端同步）

面向售后配件仓的手持作业系统：把 `配件记录.xlsx / 打包记录` 同步到 PDA，
实现**整箱扫码出库**、**扫箱盘点**、**库存清单与物料汇总导出**，并在电脑端生成箱码标签。

- Android：Kotlin + Jetpack Compose + Room（minSdk 26 / targetSdk 29，适配工业 PDA）
- 电脑端：Python FastAPI + SQLite，自动解析 Excel、增量下发、接收上传、导出报表
- 扫码：广播模式（东集 CRUISE2 等）+ HID 键盘输入双通道

## 核心规则

- **箱码 = 客户编码 + 生产单号 + 箱号**，默认 `W00305-P108580-1`；带不带分隔符都能识别
- **出库粒度 = 整箱**，每张发货单记录客户、收货人、操作员与箱内配件快照
- **盘点 = 扫箱核对**：实盘 vs 账面按配件编码汇总，输出盘亏箱与异常箱
- **库存清单保持打包记录 17 列**，另附「物料汇总」（按配件编码合计数量 / 箱数）

## 目录

```
app/                    Android 应用
  src/main/java/com/peijian/pda/
    barcode/            箱码规则与归一化匹配
    data/               Room 实体 / DAO / 仓储（导入、出库、盘点、差异）
    importer/           XLSX 直读 + 打包记录解析
    export/             XLSX 写出 + 库存/出库/盘点报表
    sync/               电脑端地址与令牌、增量拉取与上传
    scan/               扫码广播采集、震动反馈
    ui/                 Compose 页面（首页/出库/盘点/导出/记录/同步/设置）
pc-server/              电脑端同步服务（Python）
  启动同步服务.bat        一键建环境并启动
docs/                   需求、数据规则、接口协议、部署手册
配件记录.xlsx            源台账（打包记录）
```

## 快速开始

### 电脑端

```powershell
# 把 配件记录.xlsx 放在仓库根目录或 pc-server\ 下
pc-server\启动同步服务.bat
# 浏览器打开 http://127.0.0.1:8000
```

### PDA

```powershell
.\gradlew.bat assembleDebug
# 安装 app\build\outputs\apk\debug\app-debug.apk
```

PDA「数据同步」→ 扫电脑端首页的配对二维码 → 保存并测试 → 拉取新增。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/01-需求与流程.md](docs/01-需求与流程.md) | 业务背景、需求清单、出库/盘点/报表流程 |
| [docs/02-条码与数据规则.md](docs/02-条码与数据规则.md) | 箱码规则、匹配算法、Excel 17 列映射、重复箱号策略、账务口径 |
| [docs/03-同步接口协议.md](docs/03-同步接口协议.md) | 配对格式、拉取/上传接口、导出与标签接口 |
| [docs/04-部署与使用.md](docs/04-部署与使用.md) | 部署、配对、扫码枪设置、日常作业、故障排查 |
| [pc-server/README.md](pc-server/README.md) | 电脑端服务安装与接口速查 |

## 本地开发

```powershell
git clone https://github.com/qudingshun/peijian.git
cd peijian
.\gradlew.bat assembleDebug
```

- JDK 17（`gradle.properties` 固定 `org.gradle.java.home`）
- Android SDK：写入 `local.properties`（不入库）
- 发布签名：`keystore.properties`（不入库）；缺失时 release 使用 debug 签名，便于本地安装

## 约定

- 文本文件统一 LF + UTF-8（见 `.gitattributes`）
- 签名、密钥、`pc-server/data/`（含配对令牌与 SQLite）不入库
- `local.properties` / `keystore.properties` 由各机器本地维护
- 提交信息格式：`<type>: <摘要>`
