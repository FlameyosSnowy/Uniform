package io.github.flameyossnowy.uniform.json.dom;

public final class JsonShort implements JsonNumber {

    private final short value;

    public JsonShort(short value) {
        this.value = value;
    }

    @Override public byte   byteValue()   { return (byte)  value; }
    @Override public short  shortValue()  { return value; }
    @Override public int    intValue()    { return value; }
    @Override public long   longValue()   { return value; }
    @Override public float  floatValue()  { return value; }
    @Override public double doubleValue() { return value; }

    @Override
    public String toString() { return Short.toString(value); }
}
