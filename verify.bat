﻿﻿@echo off
rem Verify core algorithm against Rust test vectors (no LWJGL2 needed)
setlocal
cd /d "%~dp0"
if not exist build mkdir build
javac -encoding UTF-8 -d build src\monolith\core\*.java src\monolith\render\*.java src\monolith\Verify.java
if errorlevel 1 goto :eof
java -cp build monolith.Verify
endlocal