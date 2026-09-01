BreakHub Windows 安装说明
========================

启动：双击“BreakHub - 启动”快捷方式，或运行 BreakHub-Start.exe。
停止：双击“BreakHub - 停止”快捷方式，或运行 BreakHub-Stop.exe。

安装包已经集成 Java Runtime，用户电脑不需要安装 JDK 或 JRE。

配置文件：%LOCALAPPDATA%\BreakHub\application.yml
数据目录：%LOCALAPPDATA%\BreakHub\data
日志目录：%LOCALAPPDATA%\BreakHub\logs

首次启动会生成配置文件。默认配置只监听 127.0.0.1，默认密码和 Token
仅供本机联调；用于真实环境前必须修改全部安全值。

卸载程序只删除应用与快捷方式，不删除配置、日志和业务数据。
