@echo off
setlocal
set "BASE=%~dp0"
set "JAR=%BASE%gradle\wrapper\gradle-wrapper.jar"
if not exist "%JAR%" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%BASE%scripts\ensure-gradle-wrapper.ps1" -Root "%BASE%"
  if errorlevel 1 exit /b 1
)
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
