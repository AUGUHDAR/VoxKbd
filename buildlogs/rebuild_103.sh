#!/bin/bash
> "D:/桌面/VoxKbd/buildlogs/results.txt"
for t in fabric/1.20_1.20.1 fabric/1.20.2_1.20.6 fabric/1.21_1.21.5 fabric/1.21.6_1.21.8 fabric/1.21.9_1.21.10 fabric/1.21.11 fabric/26.1_26.1.2 fabric/26.2 fabric/26.3 forge/1.20_1.20.1 forge/1.20.2_1.20.4 forge/1.20.6 forge/1.21_1.21.5 forge/1.21.6_1.21.8 forge/1.21.9_1.21.10 forge/1.21.11 forge/26.1_26.1.2 forge/26.2 forge/26.3 neoforge/1.20.1 neoforge/1.20.4_1.20.6 neoforge/1.21_1.21.5 neoforge/1.21.6_1.21.8 neoforge/1.21.9_1.21.10 neoforge/1.21.11 neoforge/26.1_26.1.2 neoforge/26.2 neoforge/26.3; do
  echo "=== $t $(date +%H:%M:%S) ===" >> "D:/桌面/VoxKbd/buildlogs/results.txt"
  (cd "D:/桌面/VoxKbd/$t" && cmd //c "gradlew.bat build --no-daemon" > "D:/桌面/VoxKbd/buildlogs/$(echo $t | tr / _).log" 2>&1)
  if [ $? -eq 0 ]; then echo "OK $t" >> "D:/桌面/VoxKbd/buildlogs/results.txt"; else echo "FAIL $t" >> "D:/桌面/VoxKbd/buildlogs/results.txt"; fi
done
echo "ALL DONE $(date +%H:%M:%S)" >> "D:/桌面/VoxKbd/buildlogs/results.txt"
