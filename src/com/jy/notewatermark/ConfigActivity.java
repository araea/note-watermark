package com.jy.notewatermark;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Small, dependency-free configuration screen launched from the module icon. */
public final class ConfigActivity extends Activity {
    private EditText watermarkInput;
    private Switch keepBlankSpaceSwitch;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("便签分享水印");

        SharedPreferences prefs = getSharedPreferences(ConfigContract.PREFS, 0);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(28));

        TextView title = text("便签分享水印", 26, true);
        content.addView(title, matchWrap());

        TextView intro = text(
                "直接在这里改水印，不再需要手工创建 watermark.txt。修改后重新打开便签分享页即可生效。",
                15, false);
        LinearLayout.LayoutParams introParams = matchWrap();
        introParams.topMargin = dp(10);
        content.addView(intro, introParams);

        TextView label = text("水印文字", 16, true);
        LinearLayout.LayoutParams labelParams = matchWrap();
        labelParams.topMargin = dp(28);
        content.addView(label, labelParams);

        watermarkInput = new EditText(this);
        watermarkInput.setSingleLine(true);
        watermarkInput.setHint("留空即不显示水印");
        watermarkInput.setText(prefs.getString(ConfigContract.KEY_WATERMARK_TEXT, ""));
        watermarkInput.setSelection(watermarkInput.length());
        watermarkInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(80) });
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.topMargin = dp(6);
        content.addView(watermarkInput, inputParams);

        keepBlankSpaceSwitch = new Switch(this);
        keepBlankSpaceSwitch.setText("空水印时保留底部留白");
        keepBlankSpaceSwitch.setTextSize(16);
        keepBlankSpaceSwitch.setChecked(
                prefs.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true));
        LinearLayout.LayoutParams switchParams = matchWrap();
        switchParams.topMargin = dp(24);
        content.addView(keepBlankSpaceSwitch, switchParams);

        TextView hint = text(
                "开启后会隐藏水印文字和分隔线，但保留原水印区域作为约两行高的底部间距；不会改动笔记正文。关闭后则完全折叠该区域。",
                13, false);
        hint.setTextColor(Color.GRAY);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(6);
        content.addView(hint, hintParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);

        Button clear = new Button(this);
        clear.setText("清空并保存");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                watermarkInput.setText("");
                save();
            }
        });
        buttons.addView(clear, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                save();
            }
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        saveParams.leftMargin = dp(12);
        buttons.addView(save, saveParams);

        LinearLayout.LayoutParams buttonsParams = matchWrap();
        buttonsParams.topMargin = dp(28);
        content.addView(buttons, buttonsParams);

        status = text("", 14, false);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(12);
        content.addView(status, statusParams);
        updateStatus();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private void save() {
        String watermark = watermarkInput.getText().toString().trim();
        getSharedPreferences(ConfigContract.PREFS, 0).edit()
                .putString(ConfigContract.KEY_WATERMARK_TEXT, watermark)
                .putBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE,
                        keepBlankSpaceSwitch.isChecked())
                .apply();
        updateStatus();
        Toast.makeText(this, "已保存，重新打开便签分享页后生效", Toast.LENGTH_SHORT).show();
    }

    private void updateStatus() {
        String watermark = watermarkInput.getText().toString().trim();
        if (watermark.length() > 0) {
            status.setText("当前效果：显示“" + watermark + "”");
        } else if (keepBlankSpaceSwitch.isChecked()) {
            status.setText("当前效果：空水印，并保留底部留白");
        } else {
            status.setText("当前效果：完全移除水印区域");
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
