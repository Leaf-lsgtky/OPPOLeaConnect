import io

p = 'src/io/qoder/oppoemu/Core.java'
s = io.open(p, encoding='utf-8').read()

# 语义改名：cycle -> le_first（LE 没连上之前不给经典稳定立足点）
s = s.replace("    private static boolean sCycle;   // 实验：经典已连而 LE 连不上时，主动断一次经典逼耳机重开 LE 广播窗口",
              "    private static boolean sLeFirst; // LE 没连上之前持续踢掉经典：经典一连上耳机就不再见 LE 广播", 1)
s = s.replace('        sCycle = getBool("persist.oppoemu.cycle", false);',
              '        sLeFirst = getBool("persist.oppoemu.le_first", true);', 1)
s = s.replace('+ " cycle=" + sCycle\n', '+ " le_first=" + sLeFirst\n', 1)

old = """                    } else if (sCycle && dual && a2State == 2) {
                        // 经典已连而 LE 迟迟连不上（CONNECTING 也不算）：按轮累计，够 4 轮就主动拆一次经典，
                        // 耳机会因此重开包括 LE 在内的回连广播窗口
                        Integer absent = sLeAbsent.get(dev.getAddress());
                        int n = absent == null ? 0 : absent.intValue();
                        n++;
                        sLeAbsent.put(dev.getAddress(), Integer.valueOf(n));
                        if (n >= 4 && sCycled.add(dev.getAddress())) {
                            log("LE 缺席 " + n + " 轮(state=" + leState + ")，拆一次经典促使耳机重开 LE 窗口: " + dev);
                            invokeByName(a2dp, "disconnect", dev);
                            if (hs != null) {
                                invokeByName(hs, "disconnect", dev);
                            }
                        }
                    }"""
new = """                    } else if (sLeFirst && dual && a2State == 2 && leState != 2) {
                        // 经典一连上，耳机就退出回连广播（含 LE），LE 永远抢不到窗口；
                        // 所以 LE 没到手之前持续把经典踢下去，最多 sLeFirstRounds 轮后认输。
                        Integer absent = sLeAbsent.get(dev.getAddress());
                        int n = absent == null ? 0 : absent.intValue();
                        n++;
                        sLeAbsent.put(dev.getAddress(), Integer.valueOf(n));
                        if (n <= sLeFirstRounds) {
                            log("LE 未连上(state=" + leState + ")，第 " + n + "/" + sLeFirstRounds
                                    + " 轮踢掉经典让出窗口: " + dev);
                            invokeByName(a2dp, "disconnect", dev);
                            if (hs != null) {
                                invokeByName(hs, "disconnect", dev);
                            }
                        } else if (sCycled.add(dev.getAddress())) {
                            log("LE 始终连不上，放弃 LE 优先，让经典稳定: " + dev);
                        }
                    }"""
assert old in s, 'lefirst'
s = s.replace(old, new, 1)

s = s.replace("    private static final java.util.Set<String> sCycled = new java.util.HashSet<String>();",
              "    private static final java.util.Set<String> sCycled = new java.util.HashSet<String>();\n"
              "    private static int sLeFirstRounds = 8;", 1)
s = s.replace('        sLeFirst = getBool("persist.oppoemu.le_first", true);',
              '        sLeFirst = getBool("persist.oppoemu.le_first", true);\n'
              '        sLeFirstRounds = getInt("persist.oppoemu.le_first_rounds", 8);', 1)
# LE 连上后清计数，别把下一轮窗口废掉
s = s.replace("""                    if (leState == 2) {
                        sLeAbsent.remove(dev.getAddress());
                        sCycled.remove(dev.getAddress());""",
              """                    if (leState == 2) {
                        sLeAbsent.remove(dev.getAddress());
                        sCycled.remove(dev.getAddress());
                        invokeByName(a2dp, "connect", dev);""", 1)

io.open(p, 'w', encoding='utf-8').write(s)
for f, a, b in [('AndroidManifest.xml', 'versionCode="24"', 'versionCode="25"'),
                ('AndroidManifest.xml', 'versionName="2.4"', 'versionName="2.5"'),
                ('build.sh', 'VERCODE=24', 'VERCODE=25'),
                ('build.sh', 'VERNAME=2.4', 'VERNAME=2.5')]:
    t = io.open(f, encoding='utf-8').read().replace(a, b)
    io.open(f, 'w', encoding='utf-8').write(t)
print('patched v2.5')
