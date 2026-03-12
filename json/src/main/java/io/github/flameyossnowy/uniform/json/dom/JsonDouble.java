package io.github.flameyossnowy.uniform.json.dom;

public final class JsonDouble implements JsonNumber {

    private final double value;

    public JsonDouble(double value) {
        this.value = value;
    }

    @Override public byte   byteValue()   { return (byte)  value; }
    @Override public short  shortValue()  { return (short) value; }
    @Override public int    intValue()    { return (int)   value; }
    @Override public long   longValue()   { return (long)  value; }
    @Override public float  floatValue()  { return (float) value; }
    @Override public double doubleValue() { return value; }

    @Override
    public String toString() { return Double.toString(value); }
}
