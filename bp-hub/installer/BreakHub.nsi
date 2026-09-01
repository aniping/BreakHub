Unicode true
ManifestDPIAware true
RequestExecutionLevel admin

!ifndef APP_IMAGE
  !error "APP_IMAGE is required"
!endif
!ifndef OUTPUT_FILE
  !error "OUTPUT_FILE is required"
!endif
!ifndef ICON_FILE
  !error "ICON_FILE is required"
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

!define MUI_ICON "${ICON_FILE}"
!define MUI_UNICON "${ICON_FILE}"

!define PRODUCT_NAME "BreakHub"
!define PRODUCT_PUBLISHER "AteAgents"
!define PRODUCT_REGISTRY_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\BreakHub"
!define APP_COMPATIBILITY_KEY "Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers"
!define INSTALL_MARKER_FILE ".breakhub-install-root"
!define INSTALL_MARKER_VALUE "BreakHub.InstallRoot.v1"

Name "${PRODUCT_NAME}"
OutFile "${OUTPUT_FILE}"
Icon "${ICON_FILE}"
UninstallIcon "${ICON_FILE}"
InstallDir "$PROGRAMFILES64\BreakHub"
InstallDirRegKey HKLM "${PRODUCT_REGISTRY_KEY}" "InstallLocation"
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
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

Var SkipShortcuts
Var DeleteUserData

Function .onInit
  SetRegView 64
  SetShellVarContext all
  ${GetOptions} $CMDLINE "/SKIPSHORTCUTS" $0
  ${If} $0 != ""
    StrCpy $SkipShortcuts "1"
  ${EndIf}
FunctionEnd

Function un.onInit
  SetRegView 64
  SetShellVarContext all
FunctionEnd

!macro RemoveProgramFiles
  Delete "$INSTDIR\BreakHub.exe"
  Delete "$INSTDIR\BreakHub-Stop.exe"
  Delete "$INSTDIR\BreakHub-Start.exe"
  Delete "$INSTDIR\Uninstall.exe"
  Delete "$INSTDIR\README.txt"
  Delete "$INSTDIR\application.yml.template"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
!macroend

Function ValidateInstallRoot
  ClearErrors
  IfFileExists "$INSTDIR\${INSTALL_MARKER_FILE}" validate_existing validate_empty
  validate_existing:
    FileOpen $0 "$INSTDIR\${INSTALL_MARKER_FILE}" r
    IfErrors validate_invalid
    FileRead $0 $1
    FileClose $0
    StrCmp $1 "${INSTALL_MARKER_VALUE}" validate_valid validate_invalid
  validate_empty:
    IfFileExists "$INSTDIR\*.*" validate_legacy validate_valid
  validate_legacy:
    ReadRegStr $2 HKLM "${PRODUCT_REGISTRY_KEY}" "InstallLocation"
    StrCmp $2 "$INSTDIR" validate_legacy_files 0
    ReadRegStr $2 HKCU "${PRODUCT_REGISTRY_KEY}" "InstallLocation"
    StrCmp $2 "$INSTDIR" 0 validate_invalid
  validate_legacy_files:
    IfFileExists "$INSTDIR\BreakHub-Start.exe" 0 validate_invalid
    IfFileExists "$INSTDIR\BreakHub-Stop.exe" 0 validate_invalid
    IfFileExists "$INSTDIR\Uninstall.exe" 0 validate_invalid
    IfFileExists "$INSTDIR\runtime\release" validate_valid validate_invalid
  validate_invalid:
    SetErrors
  validate_valid:
FunctionEnd

Function un.ValidateInstallRoot
  ClearErrors
  IfFileExists "$INSTDIR\${INSTALL_MARKER_FILE}" 0 un_validate_invalid
  FileOpen $0 "$INSTDIR\${INSTALL_MARKER_FILE}" r
  IfErrors un_validate_invalid
  FileRead $0 $1
  FileClose $0
  StrCmp $1 "${INSTALL_MARKER_VALUE}" un_validate_valid un_validate_invalid
  un_validate_invalid:
    SetErrors
  un_validate_valid:
FunctionEnd

Section "BreakHub" MainSection
  Call ValidateInstallRoot
  IfErrors install_root_invalid install_root_valid
  install_root_invalid:
    MessageBox MB_ICONSTOP|MB_OK "请选择空目录，或选择已有的 BreakHub 安装目录。安装已取消。" /SD IDOK
    Abort
  install_root_valid:

  IfFileExists "$INSTDIR\BreakHub-Stop.exe" 0 stop_for_upgrade_done
    ExecWait '"$INSTDIR\BreakHub-Stop.exe"' $0
    ${If} $0 != 0
      MessageBox MB_ICONSTOP|MB_OK "BreakHub 未能正常停止，安装已取消。请稍后重试。" /SD IDOK
      Abort
    ${EndIf}
  stop_for_upgrade_done:

  ClearErrors
  !insertmacro RemoveProgramFiles
  IfErrors upgrade_cleanup_failed
  IfFileExists "$INSTDIR\BreakHub.exe" upgrade_cleanup_failed
  IfFileExists "$INSTDIR\BreakHub-Stop.exe" upgrade_cleanup_failed
  IfFileExists "$INSTDIR\app\*.*" upgrade_cleanup_failed
  IfFileExists "$INSTDIR\runtime\*.*" upgrade_cleanup_failed upgrade_cleanup_done
  upgrade_cleanup_failed:
    MessageBox MB_ICONSTOP|MB_OK "旧版 BreakHub 程序文件未能清理，安装已取消。请稍后重试。" /SD IDOK
    Abort
  upgrade_cleanup_done:

  SetOutPath "$INSTDIR"
  File /r "${APP_IMAGE}\*.*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  ClearErrors
  FileOpen $0 "$INSTDIR\${INSTALL_MARKER_FILE}" w
  IfErrors marker_write_failed
  FileWrite $0 "${INSTALL_MARKER_VALUE}"
  FileClose $0
  IfErrors marker_write_failed marker_write_done
  marker_write_failed:
    MessageBox MB_ICONSTOP|MB_OK "无法标记 BreakHub 安装目录，安装未完成。" /SD IDOK
    Abort
  marker_write_done:

  IfFileExists "$INSTDIR\application.yml" configuration_ready
    CopyFiles /SILENT "$INSTDIR\application.yml.template" "$INSTDIR\application.yml"
    IfFileExists "$INSTDIR\application.yml" configuration_ready configuration_failed
  configuration_failed:
    MessageBox MB_ICONSTOP|MB_OK "无法在安装目录创建 application.yml，安装未完成。" /SD IDOK
    Abort
  configuration_ready:
  CreateDirectory "$INSTDIR\data"
  CreateDirectory "$INSTDIR\logs"
  IfFileExists "$INSTDIR\data" data_ready state_directories_failed
  data_ready:
  IfFileExists "$INSTDIR\logs" logs_ready state_directories_failed
  state_directories_failed:
    MessageBox MB_ICONSTOP|MB_OK "无法在安装目录创建数据或日志目录，安装未完成。" /SD IDOK
    Abort
  logs_ready:

  WriteRegStr HKLM "${PRODUCT_REGISTRY_KEY}" "DisplayName" "${PRODUCT_NAME}"
  WriteRegStr HKLM "${PRODUCT_REGISTRY_KEY}" "DisplayVersion" "${PRODUCT_VERSION}"
  WriteRegStr HKLM "${PRODUCT_REGISTRY_KEY}" "Publisher" "${PRODUCT_PUBLISHER}"
  WriteRegStr HKLM "${PRODUCT_REGISTRY_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${PRODUCT_REGISTRY_KEY}" "DisplayIcon" "$INSTDIR\BreakHub.exe"
  WriteRegStr HKLM "${PRODUCT_REGISTRY_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKLM "${PRODUCT_REGISTRY_KEY}" "NoModify" 1
  WriteRegDWORD HKLM "${PRODUCT_REGISTRY_KEY}" "NoRepair" 1
  WriteRegStr HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub.exe" "~ RUNASADMIN"
  WriteRegStr HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub-Stop.exe" "~ RUNASADMIN"
  DeleteRegKey HKCU "${PRODUCT_REGISTRY_KEY}"

  SetShellVarContext current
  Delete "$DESKTOP\BreakHub - 启动.lnk"
  Delete "$DESKTOP\BreakHub - 停止.lnk"
  RMDir /r "$SMPROGRAMS\BreakHub"
  SetShellVarContext all
  Delete "$DESKTOP\BreakHub - 停止.lnk"
  ${If} $SkipShortcuts != "1"
    CreateDirectory "$SMPROGRAMS\BreakHub"
    CreateShortCut "$SMPROGRAMS\BreakHub\BreakHub - 启动.lnk" "$INSTDIR\BreakHub.exe" "" "$INSTDIR\BreakHub.exe"
    CreateShortCut "$SMPROGRAMS\BreakHub\BreakHub - 停止.lnk" "$INSTDIR\BreakHub-Stop.exe" "" "$INSTDIR\BreakHub-Stop.exe"
    CreateShortCut "$SMPROGRAMS\BreakHub\卸载 BreakHub.lnk" "$INSTDIR\Uninstall.exe"
    CreateShortCut "$DESKTOP\BreakHub - 启动.lnk" "$INSTDIR\BreakHub.exe" "" "$INSTDIR\BreakHub.exe"
  ${EndIf}
SectionEnd

Section "Uninstall"
  Call un.ValidateInstallRoot
  IfErrors uninstall_root_invalid uninstall_root_valid
  uninstall_root_invalid:
    MessageBox MB_ICONSTOP|MB_OK "当前目录不是有效的 BreakHub 安装目录，卸载已取消。" /SD IDOK
    Abort
  uninstall_root_valid:

  IfFileExists "$INSTDIR\BreakHub-Stop.exe" 0 stop_for_uninstall_done
    ExecWait '"$INSTDIR\BreakHub-Stop.exe"' $0
    ${If} $0 != 0
      MessageBox MB_ICONSTOP|MB_OK "BreakHub 未能正常停止，卸载已取消。请稍后重试。" /SD IDOK
      Abort
    ${EndIf}
  stop_for_uninstall_done:

  StrCpy $DeleteUserData "0"
  IfSilent uninstall_preserve_data
  MessageBox MB_ICONQUESTION|MB_YESNO|MB_DEFBUTTON2 "是否同时删除 BreakHub 的 application.yml、data 和 logs？选择“否”将保留这些数据，便于以后重新安装。" IDYES uninstall_delete_data IDNO uninstall_preserve_data
  uninstall_delete_data:
    StrCpy $DeleteUserData "1"
  uninstall_preserve_data:

  ClearErrors
  !insertmacro RemoveProgramFiles
  IfErrors uninstall_cleanup_failed
  IfFileExists "$INSTDIR\BreakHub.exe" uninstall_cleanup_failed
  IfFileExists "$INSTDIR\BreakHub-Stop.exe" uninstall_cleanup_failed
  IfFileExists "$INSTDIR\app\*.*" uninstall_cleanup_failed
  IfFileExists "$INSTDIR\runtime\*.*" uninstall_cleanup_failed uninstall_cleanup_done
  uninstall_cleanup_failed:
    MessageBox MB_ICONSTOP|MB_OK "BreakHub 程序文件未能删除，卸载未完成。请稍后重试。" /SD IDOK
    Abort
  uninstall_cleanup_done:

  StrCmp $DeleteUserData "1" 0 uninstall_data_done
    Delete "$INSTDIR\application.yml"
    RMDir /r "$INSTDIR\data"
    RMDir /r "$INSTDIR\logs"
    Delete "$INSTDIR\${INSTALL_MARKER_FILE}"
    RMDir "$INSTDIR"
  uninstall_data_done:

  SetShellVarContext current
  Delete "$DESKTOP\BreakHub - 启动.lnk"
  Delete "$DESKTOP\BreakHub - 停止.lnk"
  RMDir /r "$SMPROGRAMS\BreakHub"
  SetShellVarContext all
  Delete "$DESKTOP\BreakHub - 启动.lnk"
  Delete "$DESKTOP\BreakHub - 停止.lnk"
  RMDir /r "$SMPROGRAMS\BreakHub"
  DeleteRegValue HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub.exe"
  DeleteRegValue HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub-Stop.exe"
  DeleteRegKey HKLM "${PRODUCT_REGISTRY_KEY}"
  DeleteRegKey HKCU "${PRODUCT_REGISTRY_KEY}"
SectionEnd
