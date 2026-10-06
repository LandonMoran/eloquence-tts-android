package android.app;
public class AlertDialog {
    public static int active;
    private boolean shown;
    private final android.content.Context owner;
    public AlertDialog(android.content.Context owner) { this.owner=owner; }
    public void show() { if(!shown) { shown=true; active++; } }
    public void dismiss() { if(shown) { shown=false; active--; } }
    public static class Builder {
        private final android.content.Context owner;
        public Builder(android.content.Context owner) { this.owner=owner; }
        public Builder setView(android.view.View view) { return this; }
        public Builder setCancelable(boolean value) { return this; }
        public Builder setTitle(CharSequence title) { return this; }
        public Builder setMessage(CharSequence message) { return this; }
        public Builder setPositiveButton(int id,android.content.DialogInterface.OnClickListener listener) { return this; }
        public Builder setNegativeButton(int id,android.content.DialogInterface.OnClickListener listener) { return this; }
        public Builder setOnCancelListener(android.content.DialogInterface.OnCancelListener listener) { return this; }
        public AlertDialog create() { return new AlertDialog(owner); }
    }
}
