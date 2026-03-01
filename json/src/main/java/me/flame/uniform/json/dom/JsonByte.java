package me.flame.uniform.json.dom;

public final class JsonByte implements JsonNumber {

    private final byte value;

    public JsonByte(byte value) {
        this.value = value;
    }

    @Override public byte   byteValue()   { return value; }
    @Override public short  shortValue()  { return value; }
    @Override public int    intValue()    { return value; }
    @Override public long   longValue()   { return value; }
    @Override public float  floatValue()  { return value; }
    @Override public double doubleValue() { return value; }

    @Override
    public String toString() { return Byte.toString(value); }
}
