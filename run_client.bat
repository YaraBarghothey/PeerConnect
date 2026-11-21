@echo off
REM Starts one client GUI. Usage: run_client.bat [username] [serverIP:port]   e.g. run_client.bat sara 192.168.1.20:8888
cd /d "%~dp0"
if not exist client\target\peerconnect-client.jar (echo Run build.bat first & exit /b 1)
if "%~1"=="" (java -jar client\target\peerconnect-client.jar) else if "%~2"=="" (java -jar client\target\peerconnect-client.jar --user %~1) else (java -jar client\target\peerconnect-client.jar --user %~1 --server %~2)
