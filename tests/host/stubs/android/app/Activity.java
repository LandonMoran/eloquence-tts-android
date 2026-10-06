package android.app;
public class Activity extends android.content.Context {
    public int resultCode;
    public android.content.Intent resultData;
    public boolean finished;
    private android.content.Intent intent;
    /** Provide a no-op lifecycle hook for host activity subclasses. */
    public void onCreate(android.os.Bundle state) {}
    /** Return the request intent configured by the fixture. */
    public android.content.Intent getIntent() { return intent; }
    /** Set the request intent used by the activity under test. */
    public void setIntent(android.content.Intent value) { intent=value; }
    /** Capture the activity result code and extras for assertions. */
    public void setResult(int code, android.content.Intent data) { resultCode=code; resultData=data; }
    /** Record that the activity finished. */
    public void finish() { finished=true; }
}
