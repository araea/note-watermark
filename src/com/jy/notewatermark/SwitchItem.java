package com.jy.notewatermark;

import android.content.Context;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Switch;
import com.google.android.material.listitem.ListItemCardView;

/**
 * A segmented list item that is one on/off setting, announced as a switch. Its state is
 * kept apart from the card's own checked state, which would paint it as a selected item.
 */
public final class SwitchItem extends ListItemCardView {
    private boolean on;

    public SwitchItem(Context context) { super(context); }
    public SwitchItem(Context context, AttributeSet attrs) { super(context, attrs); }
    public SwitchItem(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }

    void setOn(boolean on) { this.on = on; }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Switch.class.getName());
        info.setCheckable(true);
        info.setChecked(on);
    }
}
