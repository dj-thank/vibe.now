@echo off
setlocal enabledelayedexpansion
set GRADLE_VERSION=9.5.0
set GRADLE_SHA256=553c78e6ddca24db466f7a14b93100b55d4b4d10f146e817f9773d6252f90b96
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set CACHE_ROOT=%GRADLE_USER_HOME%\wrapper\manual\gradle-%GRADLE_VERSION%
set ARCHIVE=%CACHE_ROOT%\gradle-%GRADLE_VERSION%-bin.zip
set DIST=%CACHE_ROOT%\gradle-%GRADLE_VERSION%
if not exist "%DIST%\bin\gradle.bat" (
  if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
  if not exist "%ARCHIVE%" powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ARCHIVE%'"
  for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%ARCHIVE%').Hash.ToLower()"') do set ACTUAL=%%H
  if not "!ACTUAL!"=="%GRADLE_SHA256%" (
    echo Gradle archive checksum mismatch. 1>&2
    del "%ARCHIVE%"
    exit /b 1
  )
  powershell -NoProfile -Command "Expand-Archive -Force '%ARCHIVE%' '%CACHE_ROOT%'"
)
call "%DIST%\bin\gradle.bat" %*
