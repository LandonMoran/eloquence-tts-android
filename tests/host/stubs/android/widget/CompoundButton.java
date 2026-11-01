package android.widget;

import android.content.Context;

/** Minimal CompoundButton state/listener surface used by the host Switch fixture. */
public class CompoundButton {
    public interface OnCheckedChangeListener {
        void onCheckedChanged(CompoundButton button, boolean checked);
    }

    protected boolean checked;
    private OnCheckedChangeListener listener;

    public CompoundButton(Context context) {}

    public boolean isChecked() { return checked; }

    public void setChecked(boolean value) {
        if (checked == value) return;
        checked = value;
        if (listener != null) listener.onCheckedChanged(this, value);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener value) { listener = value; }
}
