@echo off
REM PeerConnect - YOUR_NAME_1/YOUR_ID_1, YOUR_NAME_2/YOUR_ID_2 - builds all modules (needs JDK 17+ and Maven in PATH)
cd /d "%~dp0"
call mvn -q clean package
if errorlevel 1 (echo BUILD FAILED & exit /b 1)
echo BUILD OK: server\target\peerconnect-server.jar  client\target\peerconnect-client.jar
