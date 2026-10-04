@echo off
REM Builds the Paper plugin for Minecraft 26.3 (JDK 25).
REM Output:  build\out\DockerCraft-Reloaded-<version>.jar
REM
REM   build\build.bat           uses gradlew.bat, or gradle, or Docker
REM   build\build.bat docker    forces building inside Docker
setlocal EnableExtensions

set "ROOT=%~dp0.."
set "OUT=%~dp0out"
if "%GRADLE_IMAGE%"=="" set "GRADLE_IMAGE=gradle:jdk25"

pushd "%ROOT%\plugin" || exit /b 1

if /I "%~1"=="docker" goto :docker
if exist gradlew.bat goto :wrapper
where gradle >nul 2>nul && goto :gradle
where docker >nul 2>nul && goto :docker
echo You need Docker, or Gradle 9.8+ (Gradle downloads JDK 25 by itself).
popd & exit /b 1

:wrapper
echo ^>^> Building with gradlew.bat ...
call gradlew.bat --no-daemon build || (popd & exit /b 1)
goto :copy

:gradle
echo ^>^> Building with local gradle ...
call gradle --no-daemon build || (popd & exit /b 1)
goto :copy

:docker
where docker >nul 2>nul || (echo Docker is not installed. & popd & exit /b 1)
echo ^>^> Building inside Docker (%GRADLE_IMAGE%) ...
docker run --rm -e GRADLE_USER_HOME=/tmp/gradle-home -v "%CD%:/project" -w /project %GRADLE_IMAGE% gradle --no-daemon build || (popd & exit /b 1)
goto :copy

:copy
if not exist "%OUT%" mkdir "%OUT%"
del /q "%OUT%\DockerCraft-Reloaded-*.jar" >nul 2>nul
for %%F in (build\libs\DockerCraft-Reloaded-*.jar) do (
  echo %%~nF | findstr /C:"-plain" >nul || copy /y "%%F" "%OUT%\" >nul
)
popd
echo.
echo ^>^> Done. Copy this file to your server's plugins\ folder:
dir /b "%OUT%\DockerCraft-Reloaded-*.jar"
endlocal
