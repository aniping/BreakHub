@echo off
setlocal DisableDelayedExpansion

set "_BREAKHUB_TASK_SCRIPT=%~dp0..\..\scripts\internal\repo_tasks.py"
set "_BREAKHUB_TASK=build-mcp-exe"
"%~dp0..\..\scripts\internal\run-python-task.cmd" %*
