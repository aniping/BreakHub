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
Var ExistingInstallDirectory
Var LegacyInstallDirectory

Function .onInit
  SetRegView 64
  SetShellVarContext all
  ReadRegStr $ExistingInstallDirectory HKLM "${PRODUCT_REGISTRY_KEY}" "InstallLocation"
  ReadRegStr $LegacyInstallDirectory HKCU "${PRODUCT_REGISTRY_KEY}" "InstallLocation"
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
    FindFirst $0 $1 "$INSTDIR\*.*"
    IfErrors validate_valid
  validate_empty_loop:
    StrCmp $1 "" validate_empty_done
    StrCmp $1 "." validate_empty_next
    StrCmp $1 ".." validate_empty_next validate_legacy_found
  validate_empty_next:
    ClearErrors
    FindNext $0 $1
    IfErrors validate_empty_done validate_empty_loop
  validate_empty_done:
    FindClose $0
    Goto validate_valid
  validate_legacy_found:
    FindClose $0
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
    ClearErrors
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
    ClearErrors
FunctionEnd

Section "BreakHub" MainSection
  StrCmp $LegacyInstallDirectory "" legacy_install_check_done
  IfFileExists "$LegacyInstallDirectory\BreakHub-Stop.exe" legacy_install_detected
  IfFileExists "$LegacyInstallDirectory\BreakHub-Start.exe" legacy_install_detected legacy_install_check_done
  legacy_install_detected:
    SetErrorLevel 11
    MessageBox MB_ICONSTOP|MB_OK "检测到按用户安装的旧开发版 BreakHub。为避免迁移错误或端口冲突，请先卸载旧版并手工整理原用户目录中的配置和数据，然后重新运行本安装程序。" /SD IDOK
    Abort
  legacy_install_check_done:

  StrCmp $ExistingInstallDirectory "" install_directory_check_done
  StrCmp $ExistingInstallDirectory "$INSTDIR" install_directory_check_done install_directory_changed
  install_directory_changed:
    SetErrorLevel 12
    MessageBox MB_ICONSTOP|MB_OK "BreakHub 已安装在 $ExistingInstallDirectory。覆盖升级不能更换目录；如需更换，请先卸载现有版本。" /SD IDOK
    Abort
  install_directory_check_done:

  Call ValidateInstallRoot
  IfErrors install_root_invalid install_root_valid
  install_root_invalid:
    SetErrorLevel 13
    MessageBox MB_ICONSTOP|MB_OK "请选择空目录，或选择已有的 BreakHub 安装目录。安装已取消。" /SD IDOK
    Abort
  install_root_valid:

  IfFileExists "$INSTDIR\BreakHub-Stop.exe" 0 stop_for_upgrade_done
    ExecWait '"$INSTDIR\BreakHub-Stop.exe"' $0
    ${If} $0 != 0
      SetErrorLevel 14
      MessageBox MB_ICONSTOP|MB_OK "BreakHub 未能正常停止，安装已取消。请稍后重试。" /SD IDOK
      Abort
    ${EndIf}
  stop_for_upgrade_done:

  ClearErrors
  !insertmacro RemoveProgramFiles
  IfFileExists "$INSTDIR\BreakHub.exe" upgrade_cleanup_failed
  IfFileExists "$INSTDIR\BreakHub-Stop.exe" upgrade_cleanup_failed
  IfFileExists "$INSTDIR\app\*.*" upgrade_cleanup_failed
  IfFileExists "$INSTDIR\runtime\*.*" upgrade_cleanup_failed upgrade_cleanup_done
  upgrade_cleanup_failed:
    SetErrorLevel 15
    MessageBox MB_ICONSTOP|MB_OK "旧版 BreakHub 程序文件未能清理，安装已取消。请稍后重试。" /SD IDOK
    Abort
  upgrade_cleanup_done:

  ClearErrors
  SetOutPath "$INSTDIR"
  File /r "${APP_IMAGE}\*.*"
  IfErrors install_payload_failed
  IfFileExists "$INSTDIR\BreakHub.exe" install_start_present install_payload_failed
  install_start_present:
  IfFileExists "$INSTDIR\BreakHub-Stop.exe" install_stop_present install_payload_failed
  install_stop_present:
  IfFileExists "$INSTDIR\app\*.jar" install_jar_present install_payload_failed
  install_jar_present:
  IfFileExists "$INSTDIR\runtime\bin\server\jvm.dll" install_runtime_present install_payload_failed
  install_runtime_present:
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  IfErrors install_payload_failed
  IfFileExists "$INSTDIR\Uninstall.exe" install_payload_ready install_payload_failed
  install_payload_failed:
    SetErrorLevel 16
    !insertmacro RemoveProgramFiles
    Delete "$INSTDIR\Uninstall.exe"
    MessageBox MB_ICONSTOP|MB_OK "BreakHub 程序文件写入不完整，安装未完成。请检查磁盘空间和安全软件后重试。" /SD IDOK
    Abort
  install_payload_ready:

  IfFileExists "$INSTDIR\${INSTALL_MARKER_FILE}" marker_write_done
  ClearErrors
  Delete "$INSTDIR\${INSTALL_MARKER_FILE}.tmp"
  FileOpen $0 "$INSTDIR\${INSTALL_MARKER_FILE}.tmp" w
  IfErrors marker_write_failed
  FileWrite $0 "${INSTALL_MARKER_VALUE}"
  FileClose $0
  IfErrors marker_write_failed
  Rename "$INSTDIR\${INSTALL_MARKER_FILE}.tmp" "$INSTDIR\${INSTALL_MARKER_FILE}"
  IfErrors marker_write_failed
  IfFileExists "$INSTDIR\${INSTALL_MARKER_FILE}" marker_write_done marker_write_failed
  marker_write_failed:
    SetErrorLevel 17
    Delete "$INSTDIR\${INSTALL_MARKER_FILE}.tmp"
    !insertmacro RemoveProgramFiles
    Delete "$INSTDIR\Uninstall.exe"
    MessageBox MB_ICONSTOP|MB_OK "无法标记 BreakHub 安装目录，安装未完成。" /SD IDOK
    Abort
  marker_write_done:

  IfFileExists "$INSTDIR\application.yml" configuration_ready
    CopyFiles /SILENT "$INSTDIR\application.yml.template" "$INSTDIR\application.yml"
    IfFileExists "$INSTDIR\application.yml" configuration_ready configuration_failed
  configuration_failed:
    SetErrorLevel 18
    !insertmacro RemoveProgramFiles
    Delete "$INSTDIR\Uninstall.exe"
    MessageBox MB_ICONSTOP|MB_OK "无法在安装目录创建 application.yml，安装未完成。" /SD IDOK
    Abort
  configuration_ready:
  CreateDirectory "$INSTDIR\data"
  CreateDirectory "$INSTDIR\logs"
  IfFileExists "$INSTDIR\data" data_ready state_directories_failed
  data_ready:
  IfFileExists "$INSTDIR\logs" logs_ready state_directories_failed
  state_directories_failed:
    SetErrorLevel 19
    !insertmacro RemoveProgramFiles
    Delete "$INSTDIR\Uninstall.exe"
    MessageBox MB_ICONSTOP|MB_OK "无法在安装目录创建数据或日志目录，安装未完成。" /SD IDOK
    Abort
  logs_ready:

  ClearErrors
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
  IfErrors install_registry_failed
  ReadRegStr $0 HKLM "${PRODUCT_REGISTRY_KEY}" "InstallLocation"
  StrCmp $0 "$INSTDIR" install_registry_location_valid install_registry_failed
  install_registry_location_valid:
  ReadRegStr $0 HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub.exe"
  StrCmp $0 "~ RUNASADMIN" install_registry_start_valid install_registry_failed
  install_registry_start_valid:
  ReadRegStr $0 HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub-Stop.exe"
  StrCmp $0 "~ RUNASADMIN" install_registry_valid install_registry_failed
  install_registry_failed:
    SetErrorLevel 20
    DeleteRegValue HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub.exe"
    DeleteRegValue HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub-Stop.exe"
    DeleteRegKey HKLM "${PRODUCT_REGISTRY_KEY}"
    !insertmacro RemoveProgramFiles
    Delete "$INSTDIR\Uninstall.exe"
    MessageBox MB_ICONSTOP|MB_OK "无法登记 BreakHub 卸载项或管理员运行规则，安装未完成。程序文件已回滚，配置和数据已保留。" /SD IDOK
    Abort
  install_registry_valid:

  ReadRegStr $0 HKCU "${PRODUCT_REGISTRY_KEY}" "InstallLocation"
  StrCmp $0 "$INSTDIR" 0 legacy_registry_cleanup_done
    DeleteRegKey HKCU "${PRODUCT_REGISTRY_KEY}"
  legacy_registry_cleanup_done:

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
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd

Section "Uninstall"
  Call un.ValidateInstallRoot
  IfErrors uninstall_root_invalid uninstall_root_valid
  uninstall_root_invalid:
    SetErrorLevel 21
    MessageBox MB_ICONSTOP|MB_OK "当前目录不是有效的 BreakHub 安装目录，卸载已取消。" /SD IDOK
    Abort
  uninstall_root_valid:

  IfFileExists "$INSTDIR\BreakHub-Stop.exe" 0 stop_for_uninstall_done
    ExecWait '"$INSTDIR\BreakHub-Stop.exe"' $0
    ${If} $0 != 0
      SetErrorLevel 22
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

  StrCmp $DeleteUserData "1" 0 uninstall_data_ready
    ClearErrors
    Delete "$INSTDIR\application.yml"
    RMDir /r "$INSTDIR\data"
    RMDir /r "$INSTDIR\logs"
    IfFileExists "$INSTDIR\application.yml" uninstall_data_failed
    IfFileExists "$INSTDIR\data" uninstall_data_failed
    IfFileExists "$INSTDIR\logs" uninstall_data_failed uninstall_data_ready
  uninstall_data_failed:
    SetErrorLevel 23
    MessageBox MB_ICONSTOP|MB_OK "配置、数据或日志仍被占用，卸载已取消。程序、卸载入口和目录标记已保留；请关闭占用文件的程序后重试。" /SD IDOK
    Abort
  uninstall_data_ready:

  ClearErrors
  !insertmacro RemoveProgramFiles
  IfFileExists "$INSTDIR\BreakHub.exe" uninstall_cleanup_failed
  IfFileExists "$INSTDIR\BreakHub-Stop.exe" uninstall_cleanup_failed
  IfFileExists "$INSTDIR\README.txt" uninstall_cleanup_failed
  IfFileExists "$INSTDIR\application.yml.template" uninstall_cleanup_failed
  IfFileExists "$INSTDIR\app\*.*" uninstall_cleanup_failed
  IfFileExists "$INSTDIR\runtime\*.*" uninstall_cleanup_failed uninstall_cleanup_done
  uninstall_cleanup_failed:
    SetErrorLevel 24
    MessageBox MB_ICONSTOP|MB_OK "BreakHub 程序文件未能删除，卸载未完成。请稍后重试。" /SD IDOK
    Abort
  uninstall_cleanup_done:

  ClearErrors
  DeleteRegValue HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub.exe"
  DeleteRegValue HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub-Stop.exe"
  DeleteRegKey HKLM "${PRODUCT_REGISTRY_KEY}"
  ReadRegStr $0 HKCU "${PRODUCT_REGISTRY_KEY}" "InstallLocation"
  StrCmp $0 "$INSTDIR" 0 uninstall_legacy_registry_done
    DeleteRegKey HKCU "${PRODUCT_REGISTRY_KEY}"
    ReadRegStr $0 HKCU "${PRODUCT_REGISTRY_KEY}" "InstallLocation"
    StrCmp $0 "" uninstall_legacy_registry_done uninstall_registry_cleanup_failed
  uninstall_legacy_registry_done:
  ClearErrors
  ReadRegStr $0 HKLM "${PRODUCT_REGISTRY_KEY}" "DisplayName"
  StrCmp $0 "" uninstall_product_registry_done uninstall_registry_cleanup_failed
  uninstall_product_registry_done:
  ReadRegStr $0 HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub.exe"
  StrCmp $0 "" uninstall_start_registry_done uninstall_registry_cleanup_failed
  uninstall_start_registry_done:
  ReadRegStr $0 HKLM "${APP_COMPATIBILITY_KEY}" "$INSTDIR\BreakHub-Stop.exe"
  StrCmp $0 "" uninstall_registry_cleanup_done uninstall_registry_cleanup_failed
  uninstall_registry_cleanup_failed:
    SetErrorLevel 25
    MessageBox MB_ICONSTOP|MB_OK "卸载登记或管理员运行规则未能清理。卸载器和目录标记已保留，请检查注册表权限后重试。" /SD IDOK
    Abort
  uninstall_registry_cleanup_done:

  Delete "$INSTDIR\Uninstall.exe"
  IfFileExists "$INSTDIR\Uninstall.exe" uninstall_cleanup_failed

  SetShellVarContext current
  Delete "$DESKTOP\BreakHub - 启动.lnk"
  Delete "$DESKTOP\BreakHub - 停止.lnk"
  RMDir /r "$SMPROGRAMS\BreakHub"
  SetShellVarContext all
  Delete "$DESKTOP\BreakHub - 启动.lnk"
  Delete "$DESKTOP\BreakHub - 停止.lnk"
  RMDir /r "$SMPROGRAMS\BreakHub"

  StrCmp $DeleteUserData "1" 0 uninstall_complete
    Delete "$INSTDIR\${INSTALL_MARKER_FILE}"
    RMDir "$INSTDIR"
  uninstall_complete:
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd
