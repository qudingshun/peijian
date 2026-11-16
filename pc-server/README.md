# 电脑端同步服务（pc-server）

把「配件记录.xlsx / 打包记录」变成 PDA 可以直接拉取的接口，同时接收 PDA 上传的发货单与盘点结果。

## 一、准备

1. Windows 电脑，安装 **Python 3.10+**（安装时勾选 *Add Python to PATH*）。
2. 把 `配件记录.xlsx` 放在下列任一位置（按顺序查找）：

   | 顺序 | 位置 |
   | --- | --- |
   | 1 | `pc-server/data/config.json` 里的 `xlsx` 字段 |
   | 2 | 环境变量 `PEIJIAN_XLSX` |
   | 3 | `pc-server\配件记录.xlsx` |
   | 4 | 仓库根目录 `配件记录.xlsx` |
   | 5 | 当前工作目录 / 桌面 |

## 二、启动

双击 **`启动同步服务.bat`**。首次运行会自动：

1. 创建 `.venv` 虚拟环境；
2. 从清华镜像安装依赖（fastapi / uvicorn / openpyxl / python-barcode / qrcode）；
3. 启动服务并打开浏览器 <http://127.0.0.1:8000>。

> Windows 第一次会弹「是否允许访问网络」，必须勾选 **专用网络** 并允许，否则 PDA 连不上。
> 关闭黑窗口 = 停止服务。

手工启动（排查问题用）：

```powershell
cd pc-server
.\.venv\Scripts\python.exe -m uvicorn server:app --host 0.0.0.0 --port 8000
```

## 三、自动导入规则

- 每次 PDA 请求（`/api/v1/packing`、导出、首页）都会检查源文件的 **修改时间 + 大小**；
  变了就自动重新解析入库，**没变就不动**，不浪费时间。
- 只有内容哈希发生变化的箱才会更新，`rev` 递增，PDA 按 `rev` 增量拉取。
- 源表里的 **合并单元格会回填**（同一箱多行配件只写一次单号/箱号）。
- 同一「客户编码 + 生产单号 + 箱号」出现多行时，同一配件编码的数量自动相加。

## 四、页面功能

| 地址 | 说明 |
| --- | --- |
| `/` | 首页：箱数统计、配对二维码、导出、强制重读 |
| `/labels` | 箱码标签打印（选生产单号 + 箱号区间，Code128 或二维码） |
| `/api/v1/export/inventory.xlsx?token=…&scope=stock` | 库存清单（仅**在库**）+ 物料汇总 |
| `/api/v1/export/inventory.xlsx?token=…&scope=all` | 全部打包记录 + 物料汇总 |
| `/api/v1/export/shipments.xlsx?token=…` | 出库明细 + 发货单汇总 |

导出的「库存清单」列顺序与 `配件记录.xlsx / 打包记录` **完全一致（17 列）**，
第二页「物料汇总」按配件编码合计数量、箱数、明细行数，末行有合计。

PDA 上传发货单后，电脑端会把对应箱标记为 `SHIPPED`，之后导出的库存清单自动扣掉这些箱。

## 五、数据目录

```
pc-server/
  server.py             服务本体
  requirements.txt      依赖
  启动同步服务.bat       一键启动
  data/
    token.txt           配对令牌（首次运行生成，不要外传）
    sync.db             SQLite：箱、发货单、盘点
    config.json         可选配置 {"xlsx": "D:/共享/配件记录.xlsx"}
```

`data/` 已加入 `.gitignore`，不会提交到仓库。

## 六、常见问题

**PDA 连不上**

1. 电脑上浏览器访问 <http://127.0.0.1:8000> 是否正常；
2. 用 `ipconfig` 查电脑局域网 IP，PDA 浏览器访问 `http://<电脑IP>:8000`；
3. 关闭 Windows 防火墙对 Python 的拦截，或为 8000 端口放行；
4. 确认手机和电脑在同一网段，路由器未开启「AP 隔离」。

**提示「配对令牌不对」**

删掉 `pc-server/data/token.txt` 重启服务，重新用首页二维码配对。

**改了 Excel 没生效**

首页点「强制重新导入」，或确认文件的修改时间确实变了（Excel 另存为会更新）。

**22k 行导入要多久**

约 1~2 秒（zip + XML 直读，不用 openpyxl 解析）。首页会显示上次导入耗时。
