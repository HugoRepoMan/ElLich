@echo off
REM CONTEXTO INICIAL
REM Entrada de inicio para Windows. Sitúa la consola en la carpeta del proyecto y
REM ejecuta el lanzador Java, que prepara el entorno y arranca el servidor.
REM Los argumentos se pasan al lanzador; un puerto opcional sustituye al 8080.
REM Si falla, mantiene visible la consola para poder leer el error.
setlocal
cd /d "%~dp0"
java scripts\IniciarElLich.java %*
if errorlevel 1 (
  echo.
  echo ElLich no pudo iniciar. Revisa el error mostrado arriba.
  pause
)
endlocal
