@echo off
REM OTONOM-C3 - Cift tikla: ArduPlane SITL filosunu Elazig Havalimani'nda baslatir.
REM IHA sayisini degistirmek icin asagidaki 3'u degistir (adaptordeki sayiyla ayni olmali).
set FLEET_SIZE=3
title OTONOM-C3 - IHA'lar (%FLEET_SIZE%)
wsl.exe -d Ubuntu -e bash -ic "source ~/venv-ardupilot/bin/activate && cd ~/ardupilot/ArduPlane && ~/ardupilot/Tools/autotest/sim_vehicle.py -v ArduPlane -N --count %FLEET_SIZE% --auto-sysid --custom-location=38.6069,39.2914,890,0 --auto-offset-line 90,30 --console --map"
pause
