Unicode true
ManifestDPIAware true
RequestExecutionLevel user

!ifndef APP_IMAGE
  !error "APP_IMAGE is required"
!endif
!ifndef OUTPUT_FILE
  !error "OUTPUT_FILE is required"
!endif
!ifndef PRODUCT_VERSION
  !define PRODUCT_VERSION "0.1.0"
!endif
!ifndef PRODUCT_FILE_VERSION
  !define PRODUCT_FILE_VERSION "0.1.0.0"
!endif

!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "LogicLib.nsh"

!define PRODUCT_NAME "BreakHub"
!define PRODUCT_PUBLISHER "AteAgents"
!define PRODUCT_REGISTRY_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\BreakHub"

Name "${PRODUCT_NAME}"
OutFile "${OUTPUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\BreakHub"
SetCompressor /SOLID lzma
ShowInstDetails show
ShowUninstDetails show

VIProductVersion "${PRODUCT_FILE_VERSION}"
VIAddVersionKey /LANG=2052 "ProductName" "${PRODUCT_NAME}"
VIAddVersionKey /LANG=2052 "CompanyName" "${PRODUCT_PUBLISHER}"
VIAddVersionKey /LANG=2052 "LegalCopyright" "Copyright AteAgents"
VIAddVersionKey /LANG=2052 "FileDescription" "BreakHub Windows 安装程序"
VIAddVersionKey /LANG=2052 "FileVersion" "${PRODUCT_VERSION}"
VIAddVersionKey /LANG=2052 "ProductVersion" "${PRODUCT_VERSION}"

!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\BreakHub-Start.exe"
!define MUI_FINISHPAGE_RUN_TEXT "启动 BreakHub"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

Var SkipShortcuts

Function .onInit
  StrCpy $INSTDIR "$LOCALAPPDATA\Programs\BreakHub"
  ${GetOptions} $CMDLINE "/SKIPSHORTCUTS" $0
  ${If} $0 != ""
    StrCpy $SkipShortcuts "1"
  ${EndIf}
FunctionEnd

Section "BreakHub" MainSection
  StrCpy $INSTDIR "$LOCALAPPDATA\Programs\BreakHub"
  IfFileExists "$INSTDIR\BreakHub-Stop.exe" 0 stop_for_upgrade_done
    ExecWait '"$INSTDIR\BreakHub-Stop.exe"' $0
    ${If} $0 != 0
      MessageBox MB_ICONSTOP|MB_OK "BreakHub 未能正常停止，安装已取消。请稍后重试。" /SD IDOK
      Abort
    ${EndIf}
  stop_for_upgrade_done:

  ClearErrors
  RMDir /r "$INSTDIR"
  IfErrors upgrade_cleanup_failed
  IfFileExists "$INSTDIR\*.*" upgrade_cleanup_failed upgrade_cleanup_done
  upgrade_cleanup_failed:
    MessageBox MB_ICONSTOP|MB_OK "旧版 BreakHub 程序文件未能清理，安装已取消。请稍后重试。" /SD IDOK
    Abort
  upgrade_cleanup_done:

  SetOutPath "$INSTDIR"
  File /r "${APP_IMAGE}\*.*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  WriteRegStr HKCU "${PRODUCT_REGISTRY_KEY}" "DisplayName" "${PRODUCT_NAME}"
  WriteRegStr HKCU "${PRODUCT_REGISTRY_KEY}" "DisplayVersion" "${PRODUCT_VERSION}"
  WriteRegStr HKCU "${PRODUCT_REGISTRY_KEY}" "Publisher" "${PRODUCT_PUBLISHER}"
  WriteRegStr HKCU "${PRODUCT_REGISTRY_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${PRODUCT_REGISTRY_KEY}" "DisplayIcon" "$INSTDIR\BreakHub-Start.exe"
  WriteRegStr HKCU "${PRODUCT_REGISTRY_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${PRODUCT_REGISTRY_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${PRODUCT_REGISTRY_KEY}" "NoRepair" 1

  ${If} $SkipShortcuts != "1"
    CreateDirectory "$LOCALAPPDATA\BreakHub"
    SetOutPath "$LOCALAPPDATA\BreakHub"
    CreateDirectory "$SMPROGRAMS\BreakHub"
    CreateShortCut "$SMPROGRAMS\BreakHub\BreakHub - 启动.lnk" "$INSTDIR\BreakHub-Start.exe" "" "$INSTDIR\BreakHub-Start.exe"
    CreateShortCut "$SMPROGRAMS\BreakHub\BreakHub - 停止.lnk" "$INSTDIR\BreakHub-Stop.exe" "" "$INSTDIR\BreakHub-Stop.exe"
    CreateShortCut "$SMPROGRAMS\BreakHub\卸载 BreakHub.lnk" "$INSTDIR\Uninstall.exe"
    CreateShortCut "$DESKTOP\BreakHub - 启动.lnk" "$INSTDIR\BreakHub-Start.exe" "" "$INSTDIR\BreakHub-Start.exe"
    CreateShortCut "$DESKTOP\BreakHub - 停止.lnk" "$INSTDIR\BreakHub-Stop.exe" "" "$INSTDIR\BreakHub-Stop.exe"
  ${EndIf}
SectionEnd

Section "Uninstall"
  StrCpy $INSTDIR "$LOCALAPPDATA\Programs\BreakHub"
  IfFileExists "$INSTDIR\BreakHub-Stop.exe" 0 stop_for_uninstall_done
    ExecWait '"$INSTDIR\BreakHub-Stop.exe"' $0
    ${If} $0 != 0
      MessageBox MB_ICONSTOP|MB_OK "BreakHub 未能正常停止，卸载已取消。请稍后重试。" /SD IDOK
      Abort
    ${EndIf}
  stop_for_uninstall_done:

  ClearErrors
  RMDir /r "$INSTDIR"
  IfErrors uninstall_cleanup_failed
  IfFileExists "$INSTDIR\*.*" uninstall_cleanup_failed uninstall_cleanup_done
  uninstall_cleanup_failed:
    MessageBox MB_ICONSTOP|MB_OK "BreakHub 程序文件未能删除，卸载未完成。请稍后重试。" /SD IDOK
    Abort
  uninstall_cleanup_done:

  Delete "$DESKTOP\BreakHub - 启动.lnk"
  Delete "$DESKTOP\BreakHub - 停止.lnk"
  RMDir /r "$SMPROGRAMS\BreakHub"
  DeleteRegKey HKCU "${PRODUCT_REGISTRY_KEY}"
SectionEnd
