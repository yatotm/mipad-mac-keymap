#include "QtTrayMenu.h"
#include <QMetaObject>
#include <cstdio>

/** @brief 直接验证真实 Qt 槽发出的菜单请求，不发送鼠标事件或显示测试菜单。 */
int main(int argc, char **argv) {
  qputenv("QT_QPA_PLATFORM", "offscreen");
  QtTrayMenu menu(argc, argv);
  int popups = 0;
  QObject::connect(&menu, &QtTrayMenu::showMenu, [&popups]() { ++popups; });
  for (auto reason : {QSystemTrayIcon::Context, QSystemTrayIcon::DoubleClick, QSystemTrayIcon::MiddleClick}) {
    if (!QMetaObject::invokeMethod(&menu, "onTrayActivated", Qt::DirectConnection,
                                  Q_ARG(QSystemTrayIcon::ActivationReason, reason))) {
      return 1;
    }
  }
  if (popups != 0) { return 1; }
  if (!QMetaObject::invokeMethod(&menu, "onTrayActivated", Qt::DirectConnection,
                                Q_ARG(QSystemTrayIcon::ActivationReason, QSystemTrayIcon::Trigger))) {
    return 1;
  }
#if defined(__APPLE__)
  if (popups != 0) {
    std::fprintf(stderr, "失败：macOS 原生菜单之外又请求了手动弹出。\n");
    return 1;
  }
#else
  if (popups != 1) { return 1; }
#endif
  const int before = popups;
  menu.showMenu();
  if (popups != before + 1) { return 1; }
  std::puts("托盘激活分流检查通过：Mac 不重复弹出，显式菜单请求仍保留。");
}
