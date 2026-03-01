package me.flame.uniform.json.dom;

public final class JsonFloat implements JsonNumber {

    private final float value;

    public JsonFloat(float value) {
        this.value = value;
    }

    @Override public byte   byteValue()   { return (byte)  value; }
    @Override public short  shortValue()  { return (short) value; }
    @Override public int    intValue()    { return (int)   value; }
    @Override public long   longValue()   { return (long)  value; }
    @Override public float  floatValue()  { return value; }
    @Override public double doubleValue() { return value; }

    @Override
    public String toString() { return Float.toString(value); }
}
