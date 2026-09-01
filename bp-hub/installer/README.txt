BreakHub Windows 安装说明
========================

启动：双击“BreakHub - 启动”快捷方式，或运行 BreakHub.exe；服务就绪后会自动打开默认浏览器，启动失败时会显示诊断日志位置。
停止：双击“BreakHub - 停止”快捷方式，或运行 BreakHub-Stop.exe。

安装包已经集成私有 Java Runtime，用户电脑不需要安装 JDK 或 JRE。
Runtime 位于用户选择的 BreakHub 安装目录中，不修改系统 Java、JAVA_HOME 或 PATH。
默认安装目录为 C:\Program Files\BreakHub，安装、启动、停止和卸载时 Windows 会请求管理员授权；快捷方式和卸载项对所有用户可见。

配置文件：<安装目录>\application.yml
数据目录：<安装目录>\data
日志目录：<安装目录>\logs

安装时会生成配置文件。默认配置只监听 127.0.0.1，默认密码和 Token
仅供本机联调；用于真实环境前必须修改全部安全值。
覆盖升级必须使用原安装目录，已有配置和数据会保留。

卸载时会弹窗询问是否删除配置、日志和业务数据，默认保留；静默卸载也保留。
