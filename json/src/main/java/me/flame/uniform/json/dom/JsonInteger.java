package me.flame.uniform.json.dom;

public final class JsonInteger implements JsonNumber {

    private final int value;

    public JsonInteger(int value) {
        this.value = value;
    }

    @Override public byte   byteValue()   { return (byte)  value; }
    @Override public short  shortValue()  { return (short) value; }
    @Override public int    intValue()    { return value; }
    @Override public long   longValue()   { return value; }
    @Override public float  floatValue()  { return value; }
    @Override public double doubleValue() { return value; }

    @Override
    public String toString() { return Integer.toString(value); }
}
