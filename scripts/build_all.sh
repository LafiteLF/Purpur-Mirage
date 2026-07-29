#!/bin/bash
echo 清理旧构建缓存
./gradlew clean
echo 编译单文件服务端，跳过测试
./gradlew build -x test
echo 输出文件路径：build/release/