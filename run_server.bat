@echo off
REM Starts the server GUI. Optional: run_server.bat --headless --port 8888
cd /d "%~dp0"
if not exist server\target\peerconnect-server.jar (echo Run build.bat first & exit /b 1)
java -jar server\target\peerconnect-server.jar %*
