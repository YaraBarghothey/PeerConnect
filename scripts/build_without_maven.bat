@echo off
REM Fallback build without Maven (needs JDK 17+ in PATH). Same jars as mvn package.
cd /d "%~dp0.."
if exist build_tmp rmdir /s /q build_tmp
mkdir build_tmp\classes
dir /s /b common\*.java server\*.java client\*.java tests\*.java > build_tmp\sources.txt
javac --release 17 -encoding UTF-8 -d build_tmp\classes @build_tmp\sources.txt || exit /b 1
if not exist server\target mkdir server\target
if not exist client\target mkdir client\target
if not exist tests\target mkdir tests\target
echo Main-Class: com.peerconnect.server.ServerMain> build_tmp\m1.txt
echo Main-Class: com.peerconnect.client.ClientMain> build_tmp\m2.txt
echo Main-Class: com.peerconnect.tests.IntegrationTests> build_tmp\m3.txt
jar cfm server\target\peerconnect-server.jar build_tmp\m1.txt -C build_tmp\classes .
jar cfm client\target\peerconnect-client.jar build_tmp\m2.txt -C build_tmp\classes .
jar cfm tests\target\peerconnect-tests.jar build_tmp\m3.txt -C build_tmp\classes .
rmdir /s /q build_tmp
echo BUILD OK (javac fallback)
