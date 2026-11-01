@echo off
rem ============================================================================
rem  Dental Care - Firebase deploy
rem
rem  Double-click this file, or run it from PowerShell / Command Prompt:
rem      .\deploy.bat              deploy everything (functions, rules, indexes, storage)
rem      .\deploy.bat functions    only Cloud Functions
rem      .\deploy.bat rules        only Firestore + Storage security rules
rem      .\deploy.bat indexes      only Firestore indexes
rem      add --no-pause (e.g.  .\deploy.bat functions --no-pause)  to skip the final pause
rem
rem  Needs Node.js 22 and the Firebase CLI (npm install -g firebase-tools).
rem  Project: dental-sm (from .firebaserc). Override: set FIREBASE_PROJECT=other-id
rem ============================================================================
setlocal EnableExtensions
cd /d "%~dp0"

set "EXITCODE=0"
set "NOPAUSE="
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
if /i "%~2"=="--no-pause" set "NOPAUSE=1"
set "PROJECT=dental-sm"
if not "%FIREBASE_PROJECT%"=="" set "PROJECT=%FIREBASE_PROJECT%"

set "ONLY=functions,firestore:rules,firestore:indexes,storage"
if /i "%~1"=="functions" set "ONLY=functions"
if /i "%~1"=="rules"     set "ONLY=firestore:rules,storage"
if /i "%~1"=="indexes"   set "ONLY=firestore:indexes"

echo.
echo  Dental Care - deploying [%ONLY%] to Firebase project "%PROJECT%"
echo  ---------------------------------------------------------------
echo.

where node >nul 2>nul
if errorlevel 1 (
  echo  [X] Node.js was not found. Install Node.js 22 LTS from https://nodejs.org and try again.
  goto :fail
)
where firebase >nul 2>nul
if errorlevel 1 (
  echo  [!] Firebase CLI not found. Installing it now ^(npm install -g firebase-tools^)...
  call npm install -g firebase-tools
  if errorlevel 1 goto :fail
)

rem Sign in once if needed (opens the browser).
call firebase projects:list >nul 2>nul
if errorlevel 1 (
  echo  [!] Please sign in to Firebase in the browser window that opens...
  call firebase login
  if errorlevel 1 goto :fail
)

echo %ONLY% | find /i "functions" >nul
if not errorlevel 1 (
  echo  [1/3] Installing Cloud Functions dependencies...
  pushd functions
  call npm install --no-audit --no-fund
  if errorlevel 1 ( popd & goto :fail )
  echo  [2/3] Running checks and tests...
  call npm run check
  if errorlevel 1 ( popd & goto :fail )
  call npm test
  if errorlevel 1 ( popd & echo  [X] Tests failed - nothing was deployed. & goto :fail )
  popd
)

echo  [3/3] Deploying...
call firebase deploy --project "%PROJECT%" --only "%ONLY%"
if errorlevel 1 goto :fail

echo.
echo  [OK] Deploy finished.
goto :end

:fail
echo.
echo  [X] Deploy did not complete. Read the messages above.
set "EXITCODE=1"

:end
rem Keep the window open when started by double-click so the result can be read.
if defined NOPAUSE goto :done
echo %cmdcmdline% | find /i "%~0" >nul
if not errorlevel 1 pause
:done
endlocal & exit /b %EXITCODE%
