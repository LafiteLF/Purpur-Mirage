@echo off
chcp 65001
echo 开始清理缓存+编译Mirage服务端
.\gradlew.bat clean build -x test
echo 编译完成，成品Jar在 build\release\
pause