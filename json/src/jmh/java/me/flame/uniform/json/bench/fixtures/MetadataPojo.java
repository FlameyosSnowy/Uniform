package me.flame.uniform.json.bench.fixtures;

import me.flame.uniform.core.annotations.SerializedObject;

@SerializedObject
public class MetadataPojo {
    public String source;
    public String version;
    public int revision;
    public boolean verified;
    public String locale;
    public String timezone;
    public long lastLogin;
    public int loginCount;

    public MetadataPojo() {}

    public MetadataPojo(String source, String version, int revision, boolean verified,
                        String locale, String timezone, long lastLogin, int loginCount) {
        this.source = source; this.version = version; this.revision = revision;
        this.verified = verified; this.locale = locale; this.timezone = timezone;
        this.lastLogin = lastLogin; this.loginCount = loginCount;
    }
}