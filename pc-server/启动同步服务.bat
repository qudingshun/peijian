@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ==========================================
echo   配件管理 - 电脑端同步服务
echo ==========================================

if not exist ".venv\Scripts\python.exe" (
    echo [1/3] 首次运行：正在创建 Python 虚拟环境 ...
    python -m venv .venv
    if errorlevel 1 (
        echo.
        echo [错误] 找不到 python 命令。请先安装 Python 3.10+ 并勾选 "Add to PATH"。
        pause
        exit /b 1
    )
    echo [2/3] 正在安装依赖（清华镜像，约 1-2 分钟）...
    ".venv\Scripts\python.exe" -m pip install --upgrade pip -i https://pypi.tuna.tsinghua.edu.cn/simple
    ".venv\Scripts\python.exe" -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt
    if errorlevel 1 (
        echo.
        echo [错误] 依赖安装失败，请检查网络后重试。
        pause
        exit /b 1
    )
)

echo [3/3] 正在启动服务 ...
echo.
echo   浏览器会自动打开 http://127.0.0.1:8000
echo   第一次启动 Windows 可能提示"允许访问网络"，请勾选"专用网络"并允许。
echo   关闭本窗口即停止服务。
echo.

start "" http://127.0.0.1:8000
".venv\Scripts\python.exe" -m uvicorn server:app --host 0.0.0.0 --port 8000

pause
