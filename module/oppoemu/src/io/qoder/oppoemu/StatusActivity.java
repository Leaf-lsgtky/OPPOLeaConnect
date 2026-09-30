package io.qoder.oppoemu;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 模块自己的开关面板：写进 ConfigProvider，被 hook 的蓝牙进程在下一个事件（缓存 3 秒）读到。 */
public class StatusActivity extends Activity {

    private static final Uri CFG = Uri.parse("content://" + ConfigProvider.AUTHORITY);
    private static final String[] KEYS = {"fix_policy", "le_first", "gate_hfp", "poke",
            "hold_gatt", "adopt_ctx",
            "at", "vdsp", "vendor_id", "oesf_mask"};

    private TextView mStatus;
    private int mPad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("OppoEmu");
        mPad = (int) (14 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(mPad, mPad, mPad, mPad);

        root.addView(note("OPPO Enco X3 / LE Audio(LC3) 开关。改完不用重启蓝牙。"));

        root.addView(header("回连仲裁"));
        addSwitch(root, "fix_policy", "修被解配对写死的经典策略",
                "HyperOS 在解除配对时对每个 profile 直接写存储把 a2dp/hfp 禁掉"
                        + "（AdapterService.java:5208-5211，不经过 profile 的 setConnectionPolicy，"
                        + "所以拦 FORBIDDEN 的 hook 看不见）。落成 FORBIDDEN 后来向 page 被 okToConnect 拒、"
                        + "手动连接又被 getConnectionPolicy>0 的过滤器整条跳过，两边都连不上。"
                        + "这里在来向请求和全量连接前把它写回 ALLOWED。你在设置里主动关掉的项不会被重新打开。");
        addSwitch(root, "le_first", "LE 优先（事件级拒绝经典）",
                "开盖时耳机 BR/EDR page scan 立即应答，经典几百毫秒就先连上；而 Enco X3 一旦有经典链路"
                        + "就不再发 LE 广播，LC3 追不上。这里在 A2dpService.okToConnect 与 "
                        + "HeadsetService.okToAcceptConnection 两个入口当场拒绝（不改落盘策略），"
                        + "并推一次 LeAudioService.connect()。LE 连上立刻放行；LE 那一次尝试超时没到手"
                        + "（LeAudioStateMachine 自己 30 秒的 CONNECT_TIMEOUT）就放行经典走 AAC，"
                        + "本轮不再拦，直到下一次 LE 连上或断开。全程不计时。");
        addSwitch(root, "gate_hfp", "LE 优先时也拒绝 HFP（默认关）",
                "警告：Enco X3 主耳要靠经典 HFP/AT 协调 TWS，拒掉它会出现只有一只耳有声音。默认不拒。");
        addSwitch(root, "poke", "事件里主动推一次 LE Audio",
                "在经典来向事件里调用一次 LeAudioService.connect()，不等系统自己那约 30 秒一轮的后台连接。"
                        + "只在事件里动手，不是轮询。");

        root.addView(header("LE Audio 组解锁"));
        addSwitch(root, "adopt_ctx", "补组可用 context",
                "此 build 里 LeAudioGroupDescriptor.mAvailableContexts 从没被 native 事件填过，"
                        + "导致 isGroupAvailableForStream() 恒 false、CIG/CIS 永不建立。"
                        + "用 native AUDIO_CONF_CHANGED 事件里的 available_contexts 补上。");
        addSwitch(root, "hold_gatt", "占住 LE 链路的 GATT 持有者",
                "单耳的根因：LE Audio 连上后，只要那条 LE ACL 上一个 GATT 持有者都不剩，"
                        + "HyperOS 就会给它装 4000ms 闲置定时器并拆链（l2cu_no_dynamic_ccbs 取的是"
                        + "LCB/CCB 里最大的 idle 秒数，有持有者时 GATT 侧是 65535 所以不装表；"
                        + "拆的时候 EATT 通道和 CIS 其实都还在，日志却写着 All channels closed）。"
                        + "做法是对每个 LE 地址做一次 direct connectGatt(TRANSPORT_LE) 且不 close。"
                        + "必须 direct —— opportunistic 不进 hold-link 表（BatteryService 就是这么漏掉的）。"
                        + "代价：这条 LE 链路进不了深睡，耗电略增。");
        root.addView(header("OPPO 私有厂商 AT 通道"));
        addSwitch(root, "at", "应答厂商 AT",
                "耳机每次回连都会问 AT+VDID=? / +VDSF= / +OESF=，按 ColorOS 口径回答"
                        + "（+VDID: 1946、+VDSF: 7、+OESF=7,<值&掩码>）。");
        addSwitch(root, "vdsp", "SLC 后下发 +VDSP=1,1",
                "等价于 ColorOS 的 OplusLeAudioServiceExt.oplusHandleConnect。");
        addText(root, "vendor_id", "应答给耳机的厂商 ID（默认 1946）", "1946");
        addText(root, "oesf_mask", "+OESF 回包掩码（十六进制带 0x，默认 0x3f）", "3f");

        mStatus = new TextView(this);
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        mStatus.setPadding(0, mPad, 0, 0);
        root.addView(mStatus);

        root.addView(note("日志：adb logcat -s OppoEmu"
                + "\nLC3 实况：adb shell dumpsys bluetooth_manager"
                + " | grep -E \"cig state|CISes|Active config|Current state\""));

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        setContentView(sc);
        refresh();
    }

    private void addSwitch(LinearLayout parent, final String key, String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, mPad / 2, 0, mPad / 2);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(header(title));
        texts.addView(note(desc));
        row.addView(texts);

        Switch sw = new Switch(this);
        sw.setChecked(bool(key, def(key)));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                put(key, checked ? "true" : "false");
                refresh();
                Toast.makeText(StatusActivity.this, key + (checked ? " = 开" : " = 关"),
                        Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(sw);
        parent.addView(row);
    }

    /** 只有 gate_hfp 默认关：拒掉主耳用来协调 TWS 的经典 HFP 会出现只有一只耳有声 */
    private static boolean def(String key) {
        return !"gate_hfp".equals(key);
    }

    private void addText(LinearLayout parent, final String key, String title, String def) {
        parent.addView(note(title));
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(str(key, ""));
        et.setHint(def);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    return;
                }
                String t = et.getText().toString().trim();
                put(key, t.isEmpty() ? null : t);
                refresh();
            }
        });
        parent.addView(et);
    }

    private void refresh() {
        if (mStatus == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("当前值：");
        for (String k : KEYS) {
            sb.append(' ').append(k).append('=').append(str(k, "-"));
        }
        mStatus.setText(sb.toString());
    }

    private TextView header(String t) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.getPaint().setFakeBoldText(true);
        tv.setText(t);
        tv.setPadding(0, mPad, 0, 0);
        return tv;
    }

    private TextView note(String t) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setText(t);
        return tv;
    }

    private String str(String key, String def) {
        try {
            Bundle b = getContentResolver().call(CFG, "get", key, null);
            String v = b == null ? null : b.getString("value");
            return v == null ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    private boolean bool(String key, boolean def) {
        String v = str(key, null);
        if (v == null) {
            return def;
        }
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private void put(String key, String value) {
        try {
            Bundle extras = new Bundle();
            extras.putString("value", value);
            getContentResolver().call(CFG, "set", key, extras);
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败: " + t, Toast.LENGTH_SHORT).show();
        }
    }
}
