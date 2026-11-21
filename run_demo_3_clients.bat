@echo off
REM Opens the server and three clients (sara, adam, ali) on this PC
cd /d "%~dp0"
start "PeerConnect Server" cmd /c run_server.bat
timeout /t 3 >nul
start "sara" cmd /c run_client.bat sara
start "adam" cmd /c run_client.bat adam
start "ali" cmd /c run_client.bat ali
