@echo off
rem ============================================================
rem  Monolith Finder - compile source and package into a jar
rem  Output: monolith-finder.jar (root)
rem ============================================================
setlocal
cd /d "%~dp0"

rem clean previous build
if exist build rmdir /s /q build
mkdir build

rem LWJGL3: native libs load automatically from the natives jars on the classpath.

echo Compiling...
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d build -cp "lib\*" @sources.txt
if errorlevel 1 (
  del sources.txt
  goto :eof
)
del sources.txt

echo Packaging monolith-finder.jar (app classes only; LWJGL3 jars stay separate on the classpath)...
jar cfe monolith-finder.jar monolith.app.MonolithMapApp -C build .

echo Done. Run with run.bat [seed]
endlocal