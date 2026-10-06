package android.app;
public class Activity extends android.content.Context {
    public int resultCode;
    public android.content.Intent resultData;
    public boolean finished;
    private android.content.Intent intent;
    public void onCreate(android.os.Bundle state) {}
    public android.content.Intent getIntent() { return intent; }
    public void setIntent(android.content.Intent value) { intent=value; }
    public void setResult(int code, android.content.Intent data) { resultCode=code; resultData=data; }
    public void finish() { finished=true; }
}
