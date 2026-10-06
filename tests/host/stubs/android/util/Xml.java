package android.util;
import org.xmlpull.v1.*;
import org.kxml2.io.*;
public class Xml {
    public static int parses;
    /** Count parser creation and return a real host XML pull parser. */
    public static XmlPullParser newPullParser() { parses++; return new KXmlParser(); }
    /** Return a real host XML serializer for preference snapshots. */
    public static XmlSerializer newSerializer() { return new KXmlSerializer(); }
}
