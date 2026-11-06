package com.autoservicehub.serialization;

import com.autoservicehub.exception.FractionalQuantityException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Binds a JSON value to an {@code Integer} only when it is genuinely a whole
 * number.
 *
 * <p>Jackson's default ({@code ACCEPT_FLOAT_AS_INT}) silently truncates
 * {@code 2.9} to {@code 2}. In billing that is not a rounding detail but a
 * wrong charge: a request for 2.9 units would be invoiced for 2, with nothing
 * in the response to say so. Quantities are whole units of a part or an hour
 * of labour, and the {@code invoice_items.quantity} / {@code estimate_items.quantity}
 * columns are {@code INT}, so a fraction could not be stored even if it were
 * accepted.
 *
 * <p>Fractional input is therefore rejected with a message naming the field and
 * the value, rather than being quietly rounded. Trailing zeros are not
 * fractional for this purpose: {@code 2.000} is accepted as 2.
 *
 * <p>Implements {@link ContextualDeserializer} purely so the message can name
 * the property it rejected — Jackson supplies that here, since a deserialiser
 * declared with {@code @JsonDeserialize(using = ...)} must still be
 * constructible by Jackson's no-argument path.
 */
public class WholeNumberDeserializer extends JsonDeserializer<Integer> implements ContextualDeserializer {

    private final String fieldName;

    public WholeNumberDeserializer() {
        this("quantity");
    }

    private WholeNumberDeserializer(String fieldName) {
        this.fieldName = fieldName;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext context,
                                                BeanProperty property) {
        String name = property == null ? null : property.getName();
        return new WholeNumberDeserializer(name == null || name.isBlank() ? "quantity" : name);
    }

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();

        if (token == JsonToken.VALUE_NUMBER_INT) {
            return parser.getIntValue();
        }

        BigDecimal value;
        if (token == JsonToken.VALUE_NUMBER_FLOAT || token == JsonToken.VALUE_STRING) {
            String text = parser.getText();
            if (text == null || text.isBlank()) {
                throw new FractionalQuantityException(fieldName + " is required.");
            }
            try {
                // getDecimalValue() reads the literal exactly, so 0.1 is not
                // perturbed into 0.09999999999999999 the way a double would be.
                value = token == JsonToken.VALUE_NUMBER_FLOAT
                        ? parser.getDecimalValue()
                        : new BigDecimal(text.trim());
            } catch (NumberFormatException notANumber) {
                throw new FractionalQuantityException(
                        fieldName + " must be a whole number, got: " + text.trim());
            }
        } else {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }

        BigDecimal normalised = value.stripTrailingZeros();
        if (normalised.scale() > 0) {
            throw new FractionalQuantityException(
                    fieldName + " must be a whole number, got: " + value.toPlainString()
                    + ". Quantities are counted in whole units — split the line "
                    + "and set a unit price instead of using a fractional quantity.");
        }
        try {
            return normalised.intValueExact();
        } catch (ArithmeticException tooLarge) {
            throw new FractionalQuantityException(
                    fieldName + " is out of range, got: " + value.toPlainString());
        }
    }
}