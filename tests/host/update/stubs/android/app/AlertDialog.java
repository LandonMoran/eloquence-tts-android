package android.app;
public class AlertDialog {
    public static int active;
    private boolean shown;
    private final android.content.Context owner;
    /** Retain the dialog owner so lifecycle tests can detect leaked activity references. */
    public AlertDialog(android.content.Context owner) { this.owner=owner; }
    /** Count this dialog as active only on its first show. */
    public void show() { if(!shown) { shown=true; active++; } }
    /** Remove this dialog from the active count only if it was shown. */
    public void dismiss() { if(shown) { shown=false; active--; } }
    public static class Builder {
        private final android.content.Context owner;
        /** Retain the owner until the fixture dialog is created. */
        public Builder(android.content.Context owner) { this.owner=owner; }
        /** Accept a view without rendering it and preserve builder chaining. */
        public Builder setView(android.view.View view) { return this; }
        /** Accept cancelability without modeling user interaction. */
        public Builder setCancelable(boolean value) { return this; }
        /** Accept a title without rendering it and preserve builder chaining. */
        public Builder setTitle(CharSequence title) { return this; }
        /** Accept a message without rendering it and preserve builder chaining. */
        public Builder setMessage(CharSequence message) { return this; }
        /** Accept the positive action without invoking or retaining its listener. */
        public Builder setPositiveButton(int id,android.content.DialogInterface.OnClickListener listener) { return this; }
        /** Accept the negative action without invoking or retaining its listener. */
        public Builder setNegativeButton(int id,android.content.DialogInterface.OnClickListener listener) { return this; }
        /** Accept cancellation configuration without invoking or retaining its listener. */
        public Builder setOnCancelListener(android.content.DialogInterface.OnCancelListener listener) { return this; }
        /** Create a dialog fixture that retains this builder's owner. */
        public AlertDialog create() { return new AlertDialog(owner); }
    }
}
