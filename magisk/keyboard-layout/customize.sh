#!/system/bin/sh
# 只安装到已经读取原厂映射和验证输入链路的设备版本。
[ "$(getprop ro.product.device)" = "yudi" ] || abort "Unsupported device"
[ "$API" = "35" ] || abort "Unsupported Android version"
[ "$(getprop ro.build.version.incremental)" = "OS3.0.6.0.VMHCNXM" ] || abort "Unsupported system version"
ui_print "- Installing device-specific Fn and function-row key entries"
