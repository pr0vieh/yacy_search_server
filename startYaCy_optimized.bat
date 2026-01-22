@echo off
REM YaCy Startup Script with Optimized Settings
REM - 2GB Heap (Xmx2048m)
REM - G1GC Garbage Collector
REM - wordCacheMaxCount=50000
REM - index.maxReferences=10000
REM - Local data directory: d:\Repo\yacy

setlocal enabledelayedexpansion

set YACY_DATA=d:\Repo\yacy
set YACY_JAR=lib\yacycore.jar

echo.
echo ================================================================
echo YaCy Startup with Optimized Settings
echo ================================================================
echo Heap: 3GB (max), 1GB (init)
echo GC: G1GC
echo Word Cache: 50000 entries (optimized)
echo Max References per Term: 10000 (auto-shrinking enabled)
echo Data Directory: %YACY_DATA%
echo ================================================================
echo.

java -Xmx3072m ^
  -Xms1024m ^
  -Djava.awt.headless=true ^
  -Dsolr.directoryFactory=solr.MMapDirectoryFactory ^
  -Dfile.encoding=UTF-8 ^
  -XX:+IgnoreUnrecognizedVMOptions ^
  -XX:+UseG1GC ^
  -XX:MaxGCPauseMillis=200 ^
  -XX:InitiatingHeapOccupancyPercent=30 ^
  -XX:G1HeapRegionSize=32M ^
  -XX:+UseStringDeduplication ^
  -XX:+ParallelRefProcEnabled ^
  -XX:+AlwaysPreTouch ^
  -Xlog:gc*,safepoint:file=gc.log:time,uptime,level,tags ^
  --add-opens=java.base/java.lang=ALL-UNNAMED ^
  --add-opens=java.base/java.util=ALL-UNNAMED ^
  "-Dyacy.data=%YACY_DATA%" ^
  -classpath "%YACY_JAR%" ^
  net.yacy.yacy

echo.
echo YaCy beendet
pause
