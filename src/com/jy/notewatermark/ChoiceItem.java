package com.jy.notewatermark;

import android.content.Context;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.RadioButton;
import com.google.android.material.listitem.ListItemCardView;

/**
 * A segmented list item that is one option of a single choice. MaterialCardView names
 * itself a CardView to accessibility services after any delegate has run, so the role
 * is set here, last: screen readers then announce a radio button, checked or not.
 */
public final class ChoiceItem extends ListItemCardView {
    public ChoiceItem(Context context) { super(context); }
    public ChoiceItem(Context context, AttributeSet attrs) { super(context, attrs); }
    public ChoiceItem(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(RadioButton.class.getName());
        info.setCheckable(true);
        info.setChecked(isChecked());
    }
}
