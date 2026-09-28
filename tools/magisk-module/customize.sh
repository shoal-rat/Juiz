# Magisk 安装脚本：把 APK 放进模块的 system/priv-app/Juiz/Juiz.apk 后再打包安装。
if [ ! -f "$MODPATH/system/priv-app/Juiz/Juiz.apk" ]; then
  abort "缺少 system/priv-app/Juiz/Juiz.apk：请先把构建好的 APK 复制进来再打包"
fi
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
ui_print "- Juiz 已作为特权应用安装。重启后打开 Juiz → 设置 → 能力检测。"
