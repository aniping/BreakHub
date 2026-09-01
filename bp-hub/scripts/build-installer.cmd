@echo off
setlocal DisableDelayedExpansion

set "_BREAKHUB_TASK_SCRIPT=%~dp0..\..\scripts\internal\repo_tasks.py"
set "_BREAKHUB_TASK=package-hub-installer"
"%~dp0..\..\scripts\internal\run-python-task.cmd" %*
