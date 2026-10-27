@echo off
rem Windows launcher: keep this file next to SlimeToAnvil.jar
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java.exe") else (set "JAVA=java")
"%JAVA%" -jar "%~dp0SlimeToAnvil.jar" %*
