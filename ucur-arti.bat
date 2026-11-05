@echo off
set FLEET_SIZE=3
title OTONOM-C3 - Arti ciz (%FLEET_SIZE% IHA)
wsl.exe -d Ubuntu -e bash -ic "source ~/venv-edge/bin/activate && python /mnt/c/Users/Emirhan/Desktop/otonom-c3/sim/missions/arti_ciz.py %FLEET_SIZE%"
pause
