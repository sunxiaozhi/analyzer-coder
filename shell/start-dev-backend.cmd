@echo off
set ANALYZER_MYSQL_USER=analyzer_coder
set ANALYZER_MYSQL_PASSWORD=analyzer_coder
set ANALYZER_MYSQL_DATABASE=analyzer_coder
cd /d C:\Users\admin\Desktop\coder
C:\Users\admin\Desktop\coder\.venv\Scripts\python.exe -m web.backend.app
pause
