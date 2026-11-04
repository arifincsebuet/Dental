@echo off
rem ============================================================================
rem  Dental Care - Firebase deploy
rem
rem  Double-click this file, or run it from PowerShell / Command Prompt:
rem      .\deploy.bat              deploy everything (Firestore rules + indexes, Storage rules)
rem      .\deploy.bat rules        only Firestore + Storage security rules
rem      .\deploy.bat indexes      only Firestore indexes
rem      .\deploy.bat web          only the web app (Firebase Hosting, folder Web)
rem      add --no-pause (e.g.  .\deploy.bat rules --no-pause)  to skip the final pause
rem
rem  There are no Cloud Functions: the app works with Firestore directly, secured by
rem  firebase/firestore.rules. Needs Node.js and the Firebase CLI (npm install -g firebase-tools).
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

set "ONLY=firestore:rules,firestore:indexes,storage"
if /i "%~1"=="rules"     set "ONLY=firestore:rules,storage"
if /i "%~1"=="indexes"   set "ONLY=firestore:indexes"
if /i "%~1"=="web"       set "ONLY=hosting"

echo.
echo  Dental Care - deploying [%ONLY%] to Firebase project "%PROJECT%"
echo  ---------------------------------------------------------------
echo.

where node >nul 2>nul
if errorlevel 1 (
  echo  [X] Node.js was not found. Install Node.js LTS from https://nodejs.org and try again.
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

rem The Godot engine file is shipped in two parts (file size limits); join it once before upload.
if /i "%ONLY%"=="hosting" if exist "Web\godot\index.wasm.part1" if not exist "Web\godot\index.wasm" (
  echo  Joining the 3D engine file Web\godot\index.wasm ...
  copy /b /y "Web\godot\index.wasm.part1" + "Web\godot\index.wasm.part2" "Web\godot\index.wasm" >nul
  if errorlevel 1 goto :fail
)

echo  Deploying...
call firebase deploy --project "%PROJECT%" --only "%ONLY%"
if errorlevel 1 goto :fail

echo.
echo  [OK] Deploy finished.
if /i "%ONLY%"=="hosting" echo  Web app: https://%PROJECT%.web.app
echo  If Cloud Functions from an older version are still deployed, delete them once with:
echo      firebase functions:list --project "%PROJECT%"   then   firebase functions:delete NAME --project "%PROJECT%"
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
