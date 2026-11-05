@echo off
REM OTONOM-C3 - Cift tikla: kenar adaptorunu baslatir. Herhangi bir klasorden calisir.
REM IHA sayisi, ihalari-baslat.bat'taki sayiyla ayni olmali.
set FLEET_SIZE=3
title OTONOM-C3 - Kenar adaptoru (%FLEET_SIZE% IHA)
wsl.exe -d Ubuntu -e bash -ic "source ~/venv-edge/bin/activate && cd /mnt/c/Users/Emirhan/Desktop/otonom-c3/edge-adapter && FLEET_SIZE=%FLEET_SIZE% python -m edge_adapter.main"
pause
