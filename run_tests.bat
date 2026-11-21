@echo off
REM Runs the real-socket integration tests (server + clients on loopback)
cd /d "%~dp0"
if not exist tests\target\peerconnect-tests.jar (echo Run build.bat first & exit /b 1)
java -jar tests\target\peerconnect-tests.jar docs\test-results\integration-test-results.txt
