package com.jy.notewatermark;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
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
    private Button exportButton;
    private TextView exportStatus;

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

        TextView exportTitle = text("导出便签", 16, true);
        LinearLayout.LayoutParams exportTitleParams = matchWrap();
        exportTitleParams.topMargin = dp(36);
        content.addView(exportTitle, exportTitleParams);

        TextView exportHint = text(
                "把全部便签按分类整理成目录，打包成一个 zip 放进「下载」文件夹。"
                        + "加密便签不会被导出。",
                13, false);
        exportHint.setTextColor(Color.GRAY);
        LinearLayout.LayoutParams exportHintParams = matchWrap();
        exportHintParams.topMargin = dp(6);
        content.addView(exportHint, exportHintParams);

        exportButton = new Button(this);
        exportButton.setText("一键导出全部便签");
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startExport();
            }
        });
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        exportParams.topMargin = dp(12);
        content.addView(exportButton, exportParams);

        exportStatus = text("", 13, false);
        exportStatus.setTextColor(Color.GRAY);
        LinearLayout.LayoutParams exportStatusParams = matchWrap();
        exportStatusParams.topMargin = dp(8);
        content.addView(exportStatus, exportStatusParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    /**
     * The notes live in the Notes app's private database, so the work happens in
     * the injected hook. Querying the Notes app's exported provider both starts
     * that process and brings the result straight back.
     */
    private void startExport() {
        exportButton.setEnabled(false);
        exportStatus.setText("正在导出…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String message = exportThroughNotes();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        exportButton.setEnabled(true);
                        exportStatus.setText(message);
                        Toast.makeText(ConfigActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "note-watermark-export").start();
    }

    private String exportThroughNotes() {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    ConfigContract.EXPORT_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(ConfigContract.COLUMN_EXPORT_MESSAGE);
                if (column >= 0) {
                    return cursor.getString(column);
                }
            }
        } catch (Throwable t) {
            // fall through to the slower route below
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return requestExportOnNextStart();
    }

    /**
     * Nothing answered, which means the module is not active in the Notes app or
     * that app refuses the query. Leave the request behind and open the Notes
     * app: the hook picks it up as soon as the process starts.
     */
    private String requestExportOnNextStart() {
        getSharedPreferences(ConfigContract.PREFS, 0).edit()
                .putLong(ConfigContract.KEY_EXPORT_REQUEST, System.currentTimeMillis())
                .apply();
        Intent intent = getPackageManager().getLaunchIntentForPackage(ConfigContract.NOTE_PKG);
        if (intent == null) {
            return "导出失败：没有找到便签应用";
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Throwable t) {
            return "导出失败：无法打开便签应用（" + t + "）";
        }
        return "正在打开便签完成导出，请留意便签里的提示";
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
