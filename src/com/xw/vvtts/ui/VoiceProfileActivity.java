package com.xw.vvtts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xw.vvtts.utils.KonaVoice;
import com.xw.vvtts.utils.VoiceProfile;

/**
 * 发音角色选择。
 * 点击角色立即选中并返回；长按进入编辑对话框（自定义捏声）。
 */
public class VoiceProfileActivity extends Activity {
    private VoiceProfile voiceProfile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        voiceProfile = new VoiceProfile(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        // 顶部大幅留白，确保第一个角色（Reed）不被 ActionBar/状态栏遮挡
        root.setPadding(pad, dp(48), pad, pad);

        int curPreset = voiceProfile.getPreset();
        for (int i = 0; i < VoiceProfile.PRESET_NAMES.length; i++) {
            final int idx = i + 1;
            TextView row = new TextView(this);
            String label = VoiceProfile.PRESET_NAMES[i];
            if (idx == curPreset) label = "● " + label;
            row.setText(label);
            row.setTextSize(20);
            row.setPadding(0, dp(16), 0, dp(16));
            row.setClickable(true);
            row.setLongClickable(true);

            // 点击：选中并返回
            row.setOnClickListener(v -> {
                voiceProfile.setPreset(idx);
                Toast.makeText(this, VoiceProfile.PRESET_NAMES[idx - 1], Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });

            // 长按：编辑该角色
            row.setOnLongClickListener(v -> {
                showEditDialog(idx);
                return true;
            });

            root.addView(row);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    /** 编辑对话框：性别单选 + 6 个滑块 + 恢复默认/确定/取消 */
    private void showEditDialog(int preset) {
        KonaVoice.Voice voice = KonaVoice.byPreset(preset);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);

        // 性别单选
        TextView genderLabel = new TextView(this);
        genderLabel.setText("性别");
        genderLabel.setTextSize(14);
        box.addView(genderLabel);

        RadioGroup genderGroup = new RadioGroup(this);
        genderGroup.setOrientation(RadioGroup.HORIZONTAL);
        int curGender = voiceProfile.getParam(preset, VoiceProfile.PARAM_GENDER);
        String[] genders = {"男声", "女声"};
        for (int g = 0; g < 2; g++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(g + 1);          // 用 1=男, 2=女，避免 id=0 触发 NO_ID 异常
            rb.setText(genders[g]);
            genderGroup.addView(rb);
        }
        genderGroup.check(curGender + 1); // curGender 0/1 → id 1/2
        box.addView(genderGroup);

        // 6 个滑块参数
        final int[] cur = new int[VoiceProfile.EDITABLE_PARAMS.length];
        final SeekBar[] bars = new SeekBar[VoiceProfile.EDITABLE_PARAMS.length];
        final TextView[] vals = new TextView[VoiceProfile.EDITABLE_PARAMS.length];

        for (int i = 0; i < VoiceProfile.EDITABLE_PARAMS.length; i++) {
            int param = VoiceProfile.EDITABLE_PARAMS[i];
            cur[i] = voiceProfile.getParam(preset, param);

            TextView label = new TextView(this);
            label.setText(VoiceProfile.paramName(param));
            label.setTextSize(13);
            label.setPadding(0, dp(8), 0, 0);
            box.addView(label);

            vals[i] = new TextView(this);
            vals[i].setText(String.valueOf(cur[i]));
            vals[i].setTextSize(12);
            box.addView(vals[i]);

            SeekBar bar = new SeekBar(this);
            bar.setMax(paramMax(param));
            bar.setProgress(cur[i]);
            final int fi = i;
            final int fparam = param;
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                    vals[fi].setText(String.valueOf(progress));
                }
                @Override public void onStartTrackingTouch(SeekBar b) {}
                @Override public void onStopTrackingTouch(SeekBar b) {}
            });
            bars[i] = bar;
            box.addView(bar);
        }

        ScrollView dialogScroll = new ScrollView(this);
        dialogScroll.addView(box);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("编辑 " + voice.name)
                .setView(dialogScroll)
                .setPositiveButton("确定", (d, w) -> {
                    // 保存性别：RadioButton id 1/2 → ECI gender 0/1
                    int gender = genderGroup.getCheckedRadioButtonId() - 1;
                    if (gender < 0) gender = 0;
                    if (gender > 1) gender = 1;
                    voiceProfile.setParam(preset, VoiceProfile.PARAM_GENDER, gender);
                    // 保存 6 个滑块
                    for (int i = 0; i < VoiceProfile.EDITABLE_PARAMS.length; i++) {
                        voiceProfile.setParam(preset, VoiceProfile.EDITABLE_PARAMS[i], bars[i].getProgress());
                    }
                    Toast.makeText(this, "已保存 " + voice.name + " 的自定义参数", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", (d, w) -> {})
                .setNeutralButton("恢复默认", (d, w) -> {
                    voiceProfile.resetPreset(preset);
                    Toast.makeText(this, "已恢复 " + voice.name + " 的默认参数", Toast.LENGTH_SHORT).show();
                    // 重新打开编辑框显示默认值
                    showEditDialog(preset);
                })
                .create();
        dialog.show();
    }

    private static int paramMax(int param) {
        switch (param) {
            case VoiceProfile.PARAM_PITCH_BASE: return 120;
            case VoiceProfile.PARAM_SPEED: return 250;
            default: return 100;
        }
    }

    private int dp(int px) { return (int) (px * getResources().getDisplayMetrics().density); }
}