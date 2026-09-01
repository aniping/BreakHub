@echo off
setlocal DisableDelayedExpansion

set "_BREAKHUB_TASK_SCRIPT=%~dp0internal\repo_tasks.py"
set "_BREAKHUB_TASK=build"
"%~dp0internal\run-python-task.cmd" %*
