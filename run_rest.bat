@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

set "REST_SOURCE=%PROJECT_DIR%\src\de\affect\rest\AlmaRestServer.java"
set "REST_CLASS=%PROJECT_DIR%\lib\de\affect\rest\AlmaRestServer.class"
set "CORE_JAR=%PROJECT_DIR%\lib\affect.jar"

if not exist "%REST_SOURCE%" (
  echo [error] REST source not found: %REST_SOURCE% 1>&2
  exit /b 1
)
if not exist "%CORE_JAR%" (
  echo [error] %CORE_JAR% not found. Run build.bat first. 1>&2
  exit /b 1
)

rem ---- JDK ----
if not exist "%JAVA_HOME%\bin\javac.exe" (
  for %%J in (
    "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
    "C:\Program Files\jdk"
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
  echo [error] No JDK. Set JAVA_HOME. 1>&2
  exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ---- Classpath ----
rem lib\ takes precedence over lib\affect.jar to avoid stale manifest issues.
set "CLASSPATH=%PROJECT_DIR%\lib;%PROJECT_DIR%"
for %%J in ("%PROJECT_DIR%\lib\*.jar") do (
  if /i not "%%~nxJ"=="affect.jar" set "CLASSPATH=!CLASSPATH!;%%J"
)
for %%J in ("%PROJECT_DIR%\lib\processing\*.jar") do (
  set "CLASSPATH=!CLASSPATH!;%%J"
)

rem ---- Compile REST adaptor if needed ----
if not exist "%REST_CLASS%" (
  echo Compiling REST adaptor...
  javac -cp "%CLASSPATH%" -d "%PROJECT_DIR%\lib" "%REST_SOURCE%"
  if errorlevel 1 exit /b 1
)

echo Starting ALMA REST API (Ctrl+C to stop)
cd /d "%PROJECT_DIR%"

rem Default port: 8081 (port 8080 is held by Windows iphlpsvc on this box).
rem Skip if user already passed --port.
set "HAS_PORT=0"
for %%A in (%*) do (
  if /i "%%~A"=="--port" set "HAS_PORT=1"
)
if "!HAS_PORT!"=="0" set "EXTRA_ARGS=--port 8081"

java -Djava.awt.headless=true -cp "%CLASSPATH%" de.affect.rest.AlmaRestServer %* %EXTRA_ARGS%
