@rem Lanceur minimal du wrapper Gradle pour Windows.
@if "%DEBUG%"=="" @echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%

if defined JAVA_HOME (
  set JAVACMD="%JAVA_HOME%\bin\java.exe"
) else (
  set JAVACMD=java
)

%JAVACMD% -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
