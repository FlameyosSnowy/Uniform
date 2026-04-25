package io.github.flameyossnowy.uniform.json.parser;

import io.github.flameyossnowy.uniform.json.parser.lowlevel.ByteSlice;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.JsonCursor;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.MapJsonCursor;
import org.jetbrains.annotations.NotNull;

/**
 * JsonReadCursor: Common interface for low-level JSON cursor navigation.
 *
 * <p>Both {@link JsonCursor} (byte-array / wire-format backed) and
 * {@link MapJsonCursor} (in-memory {@code Map<String, Object>} backed)
 * implement this interface, allowing mappers and codegen-produced readers
 * to be source-agnostic.
 *
 * <p>Navigation model:
 * <pre>
 *   if (cursor.enterObject()) {
 *       while (cursor.nextField()) {
 *           switch (cursor.fieldNameHash()) {
 *               case HASH_FOO:
 *                   if (cursor.fieldNameEquals("foo")) foo = cursor.fieldValueAsInt();
 *                   break;
 *               ...
 *           }
 *       }
 *   }
 * </pre>
 *
 * Array iteration:
 * <pre>
 *   JsonReadCursor arr = cursor.fieldValueCursor();
 *   if (arr.enterArray()) {
 *       while (arr.nextElement()) {
 *           list.add(arr.elementValueAsInt());
 *       }
 *   }
 * </pre>
 */
@SuppressWarnings("unused")
public interface JsonReadCursor {

    /**
     * Advances past the opening {@code '{'} of an object.
     *
     * @return {@code true} if an object was entered; {@code false} if the
     *         current value is not an object (the cursor position is unchanged).
     */
    boolean enterObject();

    /**
     * Advances to the next field of the current object, populating the
     * field-name and field-value slots.
     *
     * @return {@code true} if another field was found; {@code false} when the
     *         closing {@code '}'} (or end of map) is reached.
     */
    boolean nextField();

    /**
     * Advances past the opening {@code '['} of an array.
     *
     * @return {@code true} if an array was entered; {@code false} if the
     *         current value is not an array.
     */
    boolean enterArray();

    /**
     * Advances to the next element of the current array, populating the
     * element-value slot.
     *
     * @return {@code true} if another element was found; {@code false} when
     *         the closing {@code ']'} (or end of list) is reached.
     */
    boolean nextElement();

    void skipFieldValue();

    /**
     * Returns the current field name as a {@link ByteSlice}.
     * Only valid after a successful {@link #nextField()} call.
     */
    @NotNull ByteSlice fieldName();

    /**
     * Returns the current field name as a {@link String} without allocating
     * an intermediate {@link ByteSlice}.
     */
    @NotNull String fieldNameAsString();

    /**
     * FNV-1a hash of the current field name bytes.
     * Used by generated switch-on-hash dispatch in codegen-produced readers.
     */
    int fieldNameHash();

    /**
     * Returns {@code true} if the current field name is byte-for-byte equal
     * to {@code expected}.
     */
    boolean fieldNameEquals(@NotNull String expected);

    /** Returns the raw current field value as a {@link ByteSlice}. */
    @NotNull ByteSlice fieldValue();

    int     fieldValueAsInt();
    long    fieldValueAsLong();
    double  fieldValueAsDouble();
    float   fieldValueAsFloat();
    short   fieldValueAsShort();
    byte    fieldValueAsByte();
    boolean fieldValueAsBoolean();

    /**
     * Returns the current field value as a {@link String}, stripping
     * surrounding JSON quotes if present.
     */
    @NotNull String fieldValueAsUnquotedString();

    /**
     * Returns a new cursor positioned at the start of the current field's
     * value. Used for nested object / array fields.
     */
    @NotNull JsonReadCursor fieldValueCursor();

    /** Returns the raw current array element as a {@link ByteSlice}. */
    @NotNull ByteSlice elementValue();

    int     elementValueAsInt();
    long    elementValueAsLong();
    double  elementValueAsDouble();
    float   elementValueAsFloat();
    short   elementValueAsShort();
    byte    elementValueAsByte();
    boolean elementValueAsBoolean();

    /**
     * Returns the current array element as a {@link String}, stripping
     * surrounding JSON quotes if present.
     */
    @NotNull String elementValueAsUnquotedString();

    /**
     * Returns a new cursor positioned at the start of the current array
     * element's value. Used for nested object / array elements.
     */
    @NotNull JsonReadCursor elementValueCursor();

    default char fieldValueAsChar() {
        return (char) (elementValueAsByte() & 255);
    }

    boolean elementIsNull();
}