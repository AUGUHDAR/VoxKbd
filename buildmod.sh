#!/bin/bash
cd "D:/桌面/VoxKbd"
export JAVA_HOME="C:/Program Files/Java/jdk-25.0.2"
GBAT='D:\.gradle\wrapper\dists\gradle-9.7.0-bin\d4tj7w02tcgubx9zk9hbippn6\gradle-9.7.0\bin\gradle.bat'
exec cmd.exe //c "$GBAT voxkbd-mod:build --console=plain"
