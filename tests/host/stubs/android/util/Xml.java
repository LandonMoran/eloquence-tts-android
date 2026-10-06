package android.util;
import org.xmlpull.v1.*;
import org.kxml2.io.*;
public class Xml {
    public static int parses;
    public static XmlPullParser newPullParser() { parses++; return new KXmlParser(); }
    public static XmlSerializer newSerializer() { return new KXmlSerializer(); }
}
