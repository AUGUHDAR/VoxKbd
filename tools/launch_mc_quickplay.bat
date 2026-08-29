@echo off
setlocal
set "GAME_DIR=D:\桌面\voxlink\客户端测试"
set "VERSION=26.2-Fabric 0.19.3"
set "JAVA=C:\Program Files\Java\jdk-25.0.2\bin\java.exe"
set "WORLD=新的世界"
set "CP_FILE=%GAME_DIR%\voxkbd_cp.txt"
set "ARGS_FILE=%GAME_DIR%\voxkbd_args.txt"
python "%~dp0build_mc_classpath.py" "%GAME_DIR%" "%VERSION%" > "%CP_FILE%"
if errorlevel 1 ( echo [FATAL] classpath build failed & exit /b 1 )
> "%ARGS_FILE%" echo --version %VERSION%
>>"%ARGS_FILE%" echo --gameDir "%GAME_DIR%"
>>"%ARGS_FILE%" echo --assetsDir "%GAME_DIR%\assets"
>>"%ARGS_FILE%" echo --quickPlaySingleplayer "%WORLD%"
>>"%ARGS_FILE%" echo --quickPlayPath "%GAME_DIR%\saves\%WORLD%"
"%JAVA%" -Xms2G -Xmx4G "-Djava.library.path=%GAME_DIR%\versions\%VERSION%\%VERSION%-natives" "-Dorg.lwjgl.librarypath=%GAME_DIR%\versions\%VERSION%\%VERSION%-natives" "-cp" "@%CP_FILE%" net.fabricmc.loader.impl.launch.knot.KnotClient "@%ARGS_FILE%"
endlocal
