@echo off
setlocal DisableDelayedExpansion

set "_BREAKHUB_TASK_SCRIPT=%~dp0..\internal\repo_tasks.py"
set "_BREAKHUB_TASK=build-manager-exe"
"%~dp0..\internal\run-python-task.cmd" %*
