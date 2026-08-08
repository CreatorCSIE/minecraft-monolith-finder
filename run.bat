@echo off
rem ============================================================
rem  Monolith Finder - run the packaged jar
rem  Usage: run.bat [seed]
rem  e.g.   run.bat 8676641231682978167
rem  Requires: monolith-finder.jar (built by compile.bat)
rem ============================================================
setlocal
cd /d "%~dp0"

if not exist monolith-finder.jar (
  echo monolith-finder.jar not found. Run compile.bat first.
  goto :eof
)

java "-Djava.library.path=natives" -jar monolith-finder.jar %*

endlocal