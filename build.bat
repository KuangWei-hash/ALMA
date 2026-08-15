@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

rem ---- JDK ----
if not exist "%JAVA_HOME%\bin\javac.exe" (
  for %%J in (
    "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
    "C:\Program Files\jdk"
    "C:\Program Files\Java\jdk-17"
  ) do (
    if exist "%%~J\bin\javac.exe" set "JAVA_HOME=%%~J"
  )
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
  for /f "delims=" %%B in ('where javac.exe 2^>nul') do (
    for %%P in ("%%B") do set "JAVA_HOME=%%~dpP.."
  )
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
  echo [error] No JDK. Set JAVA_HOME or install JDK 17. 1>&2
  exit /b 1
)

rem ---- Ant ----
if not exist "%ANT_HOME%\bin\ant.bat" (
  for %%A in (
    "C:\Users\dinos\.p2\pool\plugins\org.apache.ant_1.10.12.v20211102-1452"
  ) do (
    if exist "%%~A\bin\ant.bat" set "ANT_HOME=%%~A"
  )
)
if not exist "%ANT_HOME%\bin\ant.bat" (
  for /f "delims=" %%B in ('where ant.bat 2^>nul') do (
    for %%P in ("%%B") do set "ANT_HOME=%%~dpP.."
  )
)
if not exist "%ANT_HOME%\bin\ant.bat" (
  echo [error] No Ant. Set ANT_HOME or put ant.bat on PATH. 1>&2
  exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%ANT_HOME%\bin;%PATH%"

set "MODE=%~1"
if "%MODE%"=="" set "MODE=build"
if /i "%MODE%"=="help"   goto :usage
if /i "%MODE%"=="-h"     goto :usage
if /i "%MODE%"=="--help" goto :usage

set "TARGETS=jar"
if /i "%MODE%"=="build"   set "TARGETS=jar"
if /i "%MODE%"=="jar"     set "TARGETS=jar"
if /i "%MODE%"=="fast"    set "TARGETS=jar-fast"
if /i "%MODE%"=="clean"   set "TARGETS=clean"
if /i "%MODE%"=="rebuild" set "TARGETS=clean jar"
if /i "%MODE%"=="test"    set "TARGETS=jar run-output-example"

echo ALMA build
echo   JAVA_HOME: %JAVA_HOME%
echo   ANT_HOME:  %ANT_HOME%
java -version
echo   Target:    %TARGETS%
echo.

pushd "%PROJECT_DIR%"
ant -f bin\build.xml %TARGETS%
set "RC=%ERRORLEVEL%"
popd

if /i not "%MODE%"=="clean" if exist "%PROJECT_DIR%\lib\affect.jar" (
  echo Build complete: %PROJECT_DIR%\lib\affect.jar
) else (
  echo Done.
)
exit /b %RC%

:usage
echo Usage: build.bat [mode]
echo.
echo   build, jar   Full build, produces lib\affect.jar  (default)
echo   fast         Fast build, reuse existing XMLBeans artifacts
echo   clean        Remove build outputs
echo   rebuild      Clean then full build
echo   test         Build then run headless example test
echo   ^<target^>    Pass through to bin\build.xml
echo   help         Show this message
echo.
echo Environment:
echo   JAVA_HOME   JDK install root; auto-detected if unset
echo   ANT_HOME    Ant install root; auto-detected if unset
exit /b 0
