@echo off
setlocal
set GRADLE_VERSION=8.13
if not defined GRADLE_USER_HOME set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set GRADLE_HOME_DIR=%GRADLE_USER_HOME%\gson-bootstrap\gradle-%GRADLE_VERSION%
set GRADLE_BIN=%GRADLE_HOME_DIR%\bin\gradle.bat
if not exist "%GRADLE_BIN%" (
  if not exist "%GRADLE_USER_HOME%\gson-bootstrap" mkdir "%GRADLE_USER_HOME%\gson-bootstrap"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$u='https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip'; $z=Join-Path $env:TEMP 'gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest $u -OutFile $z; Expand-Archive -Force $z '%GRADLE_USER_HOME%\gson-bootstrap'; Remove-Item $z"
  if errorlevel 1 exit /b 1
)
call "%GRADLE_BIN%" %*
